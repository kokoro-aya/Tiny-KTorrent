package moe.irony.workers

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.channels.Channel
import moe.irony.bencode_decoder.Peer
import moe.irony.connect.*
import moe.irony.pieces.PieceManager
import moe.irony.utils.Log
import moe.irony.utils.bytesToInt
import moe.irony.utils.hexDecode
import moe.irony.utils.intToBytes
import java.util.concurrent.ConcurrentLinkedDeque

const val INFO_HASH_STARTING_POS = 28
const val PEER_ID_STARTING_POS = 48
const val HASH_LENGTH = 20
const val DUMMY_PEER_IP = "0.0.0.0"

class Worker(
    private val peers: Channel<Peer>,
    private val clientId: String,
    private val infoHash: String,
    private val pieceManager: PieceManager,
) {
    private lateinit var socket: Socket
    private lateinit var outputChannel: ByteWriteChannel
    private lateinit var inputChannel: ByteReadChannel

    private var choked: Boolean = true
    private var terminated = false
    private var requestPending = false

    private lateinit var peer: Peer
    private lateinit var peerBitField: String

    lateinit var peerId: String private set

    fun createHandshakeMessage(): String {
        val protoLen = (0x13).toChar()
        val protocol = "BitTorrent protocol"
        val zero = (0x0).toChar()

        val message = buildString {
            append(protoLen)
            append(protocol)
            for (i in 0 until 8) append(zero)
            append(infoHash.hexDecode())
            append(clientId)
        }
        check (message.length == protocol.length + 49) { "Wrong handshake message!" }

        return message
    }

    suspend fun performHandshake() {
        Log.info { "Connecting to peer [${peer.ip}]..." }
        try {
            socket = createConnection(peer.ip, peer.port)
            outputChannel = socket.openWriteChannel(autoFlush = true)
            inputChannel = socket.openReadChannel()
        } catch (e: Exception) {
            socket.close()
            throw RuntimeException("Cannot connect to peer [${peer.ip}]")
        }
        Log.info { "Establish TCP connection with peer: SUCCESS" }

        Log.info { "Sending handshake message to [${peer.ip}]..." }
        val handshakeMessage = createHandshakeMessage()
        sendData(outputChannel, handshakeMessage)
        Log.info { "Send handshake message: SUCCESS" }

        Log.info { "Receiving handshake reply from peer [${peer.ip}]" }
        val reply = recvData(inputChannel, handshakeMessage.length)
        if (reply.isEmpty()) {
            socket.close()
            throw RuntimeException("Receive handshake from peer: FAILED [No response from peer]")
        }

        peerId = reply.slice(PEER_ID_STARTING_POS until PEER_ID_STARTING_POS + HASH_LENGTH)
        Log.info { "Receive handshake reply from peer: SUCCESS" }

        val receivedInfoHash = reply.slice(INFO_HASH_STARTING_POS until INFO_HASH_STARTING_POS + HASH_LENGTH)
        if (receivedInfoHash != infoHash) {
            socket.close()
            throw RuntimeException("Perform handshake with peer ${peer.ip}:" +
                    " FAILED [Received mismatching info hash]" +
                    "this: $infoHash, remote: $receivedInfoHash")
        }

        Log.info { "Hash comparison: SUCCESS" }
    }

    suspend fun receiveBitField() {
        Log.info { "Receiving BitField message from peer [${peer.ip}]..." }
        val message = receiveMessage()
        if (message.id != MessageId.BITFIELD)
            throw RuntimeException("Receive BitField from peer: FAILED [ wrong message ID: ${message.id} ]")
        peerBitField = message.payload
        pieceManager.addPeer(peerId, peerBitField)

        Log.info { "Receive BitField from peer: SUCCESS" }
    }

    suspend fun sendInterested() {
        Log.info { "Sending Interested message to peer [${peer.ip}]" }
        val interestedMessage = BitTorrentMessage(MessageId.INTERESTED, "").toString()
        sendData(outputChannel, interestedMessage)
        Log.info { "Send Interested message: SUCCESS" }
    }

    suspend fun receiveUnchoke() {
        Log.info { "Receiving Unchoke message from peer [${peer.ip}]..." }
        val message = receiveMessage()
        if (message.id != MessageId.UNCHOKE)
            throw RuntimeException("Receive Unchoke message from peer: FAILED [ wrong message ID: ${message.id} ]")
        choked = false
        Log.info { "Received Unchoke message: SUCCESS" }
    }

    suspend fun requestPiece() {
        val block = pieceManager.nextRequest(peerId)
        block ?.let {
            // Java内部使用网络序，不需要htonl转换
            val index = block.piece.intToBytes().map { it.toByte() }
            val offset = block.offset.intToBytes().map { it.toByte() }
            val length = block.length.intToBytes().map { it.toByte() }

            val payload = (index + offset + length).joinToString("")

            buildString {
                append("Sending Request message to peer ${peer.ip} ")
                append("[Piece: ${block.piece}, Offset: ${block.offset}, Length: ${block.length}]")
                appendLine()
            }.let { Log.info { it } }

            val requestMessage = BitTorrentMessage(MessageId.REQUEST, payload).toString()
            sendData(outputChannel, requestMessage)
            requestPending = true
            Log.info { "Send Request message: SUCCESS" }
        }
    }

    suspend fun closeSocket() {
        if (!socket.isClosed) {
            Log.info { "Closing connection at socket ${socket.localAddress}" }
            socket.close()
            requestPending = false
            if (peerBitField.isNotEmpty()) {
                peerBitField = ""
                pieceManager.removePeer(peerId)
            }
        }
    }

    suspend fun establishNewConnection(): Boolean {
        return try {
            performHandshake()
            receiveBitField()
            sendInterested()
            true
        } catch (e: Exception) {
            Log.error { "An error occurred while connecting with peer [${peer.ip}]" }
            Log.error { e.message ?: "" }
            false
        }
    }

    suspend fun receiveMessage(bufferSize: Int = 0): BitTorrentMessage {
        val reply = recvData(inputChannel, bufferSize)
        if (reply.isEmpty())
            return BitTorrentMessage(MessageId.KEEP_ALIVE, reply)
        val messageId = when (reply[0].code) {
            0 -> MessageId.CHOKE
            1 -> MessageId.UNCHOKE
            2 -> MessageId.INTERESTED
            3 -> MessageId.NOT_INTERESTED
            4 -> MessageId.HAVE
            5 -> MessageId.BITFIELD
            6 -> MessageId.REQUEST
            7 -> MessageId.PIECE
            8 -> MessageId.CANCEL
            9 -> MessageId.PORT
            else -> throw IllegalArgumentException("Invalid message id received")
        }
        val payload = reply.substring(1)
        Log.info { "Received message with ID $messageId from peer [${peer.ip}]" }
        return BitTorrentMessage(messageId, payload)
    }

    suspend fun start() {
        Log.info { "Downloading thread started..." }
        while (!(terminated || pieceManager.isComplete())) {

            peer = peers.receive()

            if (peer.ip == DUMMY_PEER_IP)
                return

            try {
                if (establishNewConnection()) {
                    while (!pieceManager.isComplete()) {
                        val message = receiveMessage()
                        when (message.id) {
                            MessageId.CHOKE -> {
                                choked = true
                            }
                            MessageId.UNCHOKE -> {
                                choked = false
                            }
                            MessageId.HAVE -> {
                                val payload = message.payload
                                val pieceIndex = payload.map { it.code.toByte().toUByte() }.bytesToInt()
                                pieceManager.updatePeer(peerId, pieceIndex)
                            }
                            MessageId.PIECE -> {
                                requestPending = false
                                val payload = message.payload
                                val index = payload.slice(0 until 4).map { it.code.toByte().toUByte() }.bytesToInt()
                                val begin = payload.slice(4 until 8).map { it.code.toByte().toUByte() }.bytesToInt()
                                val blockData = payload.substring(8)
                                pieceManager.blockReceived(peerId, index, begin, blockData)
                            }
                            else -> {
                                Log.error { "Unsupported BitMessageId: ${message.id}, aborted" }
                                break
                            }
                        }
                        if (!choked && !requestPending) {
                            requestPiece()
                        }
                    }
                }
            } catch (e: Exception) {
                closeSocket()
                Log.error { "An error occurred while downloading from peer $peerId [${peer.ip}]" }
                Log.error { e.message ?: "" }
            }
        }
    }

    fun stop() {
        terminated = true
    }

}