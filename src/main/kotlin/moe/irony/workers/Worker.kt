package moe.irony.workers

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import moe.irony.bencode_decoder.Peer
import moe.irony.connect.*
import moe.irony.pieces.PieceManager
import moe.irony.utils.*
import java.util.concurrent.ConcurrentLinkedDeque

const val INFO_HASH_STARTING_POS = 28
const val PEER_ID_STARTING_POS = 48
const val HASH_LENGTH = 20
const val DUMMY_PEER_IP = "0.0.0.0"

/***
 * Represents a concrect worker that works on a thread that is connected to a server and tries to download blocks as the
 * download is not completed.
 *
 * It exposes two methods `start()` and `stop()`. The `start()` method should be wrapped into a coroutine launcher to make
 * full use of coroutines.
 *
 * @param peers a channel of peers that this worker will listen to get the next available peer
 * @param clientId the peer ID of this BitTorrent client. It's generated in the TorrentClient class
 * @param infoHash the info hash of the torrent file
 * @param pieceManager a PieceManager that manages the workers
 */
class Worker(
    private val peers: Channel<Peer>, // we use the channel instead of a queue
    private val clientId: String,
    private val infoHash: String,
    private val pieceManager: PieceManager,
) {

    // Sockets and channels. Must be initialized before usage.

    private lateinit var socket: Socket
    private lateinit var outputChannel: ByteWriteChannel
    private lateinit var inputChannel: ByteReadChannel

    // Internal flags

    private var choked: Boolean = true
    private var terminated = false
    private var requestPending = false

    // The peer and its BitField that this worker holds. Must be initialized before usage.

    private lateinit var peer: Peer
    private lateinit var peerBitField: String

    // The id of the peer that we are connecting to
    lateinit var peerId: String private set

    /**
     * Creates the initial handshake message to be sent to the peer.
     * The handshake message has the following structure:
     *
     * <proto.length><proto><reserved><info_hash><peer_id>
     *
     * proto: string identifier of the protocol
     * proto.length: the length of proto, in a single raw byte
     * reserved: 8 reserved bytes
     * info_hash: 20-byte sha1 hash of the torrent file
     * peer_id: 20-byte string used as a unique ID for the client
     *
     * See https://blog.jse.li/posts/torrent/#complete-the-handshake for more descriptions.
     *
     * @return a string representation of the handshake message
     */
    private fun createHandshakeMessage(): String {
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

    /**
     * Establishes a TCP connection with the peer and send an initial BitTorrent handshake message.
     * Then it waits for it to reply, compares its info hash to that of the torrent file.
     * If the hashes do not match, the connection will be closed.
     */
    private suspend fun performHandshake() {

        // Connects to the peer
        Log.info { "Connecting to peer [${peer.ip}]..." }
        try {
            socket = createConnection(peer.ip, peer.port)
            outputChannel = socket.openWriteChannel(autoFlush = true)
            inputChannel = socket.openReadChannel()
        } catch (e: Exception) {
            socket.close()
            Log.error { e.message ?: "An error occurred while performing handshake with peer [${peer.ip}]" }
            throw RuntimeException("Cannot connect to peer [${peer.ip}]")
        }
        Log.info { "Establish TCP connection with peer: SUCCESS" }

        // Send the handshake message to the peer
        Log.info { "Sending handshake message to [${peer.ip}]..." }
        val handshakeMessage = createHandshakeMessage()
        sendData(outputChannel, handshakeMessage)
        Log.info { "Send handshake message: SUCCESS" }

        // Waiting for response from the peer
        Log.info { "Receiving handshake reply from peer [${peer.ip}]" }
        val reply = recvData(inputChannel, handshakeMessage.length)
        if (reply.isEmpty()) {
            socket.close()
            throw RuntimeException("Receive handshake from peer: FAILED [No response from peer]")
        }

        // Compare the info hash from the peer's reply message with that we sent.
        // If the two are mismatched, close the connection and raise an exception.
        peerId = reply.slice(PEER_ID_STARTING_POS until PEER_ID_STARTING_POS + HASH_LENGTH)
        Log.info { "Receive handshake reply from peer: SUCCESS" }

        val receivedInfoHash = reply.slice(INFO_HASH_STARTING_POS until INFO_HASH_STARTING_POS + HASH_LENGTH)
        if (receivedInfoHash.map { it.code.toByte() }
            != infoHash.hexDecode().map { it.code.toByte() }) { // 很麻烦。。。但是不这样就没法匹配了（
            socket.close()
            throw RuntimeException("Perform handshake with peer ${peer.ip}:" +
                    " FAILED [Received mismatching info hash]" +
                    "this: $infoHash, remote: $receivedInfoHash")
        }

        Log.info { "Hash comparison: SUCCESS" }
    }

    /**
     * Receives and read the message that contains a BitField from the peer
     */
    private suspend fun receiveBitField() {

        // Receive BitField from the peer
        Log.info { "Receiving BitField message from peer [${peer.ip}]..." }
        val message = receiveMessage()
        if (message.id != MessageId.BITFIELD)
            throw RuntimeException("Receive BitField from peer: FAILED [ wrong message ID: ${message.id} ]")
        peerBitField = message.payload

        // Informs the PieceManager the received BitField
        pieceManager.addPeer(peerId, peerBitField)

        Log.info { "Receive BitField from peer: SUCCESS" }
    }

    /**
     * Send an Interested message to the peer.
     */
    private suspend fun sendInterested() {
        Log.info { "Sending Interested message to peer [${peer.ip}]" }
        val interestedMessage = BitTorrentMessage(MessageId.INTERESTED, "").toString()
        sendData(outputChannel, interestedMessage)
        Log.info { "Send Interested message: SUCCESS" }
    }

    /**
     * Receives and read the Unchoke message from the peer. If the received message is not Unchoke, raise an error.
     */
    private suspend fun receiveUnchoke() {
        Log.info { "Receiving Unchoke message from peer [${peer.ip}]..." }
        val message = receiveMessage()
        if (message.id != MessageId.UNCHOKE)
            throw RuntimeException("Receive Unchoke message from peer: FAILED [ wrong message ID: ${message.id} ]")
        choked = false
        Log.info { "Received Unchoke message: SUCCESS" }
    }

    /**
     * Sends a request message to the peer for the next block to be downloaded.
     */
    private suspend fun requestPiece() {
        val block = pieceManager.nextRequest(peerId)
        block ?.let {
            // Java内部使用网络序，不需要htonl转换
//            val index = block.piece.intToBytes().map { it.toByte() }
//            val offset = block.offset.intToBytes().map { it.toByte() }
//            val length = block.length.intToBytes().map { it.toByte() }
//
//            val payload = (index + offset + length).joinToString("")

            // 这里有好几个坑
            // - index，offset，length长度不一定为完整的int（4个字节），然后就会出现长度不一致
            // - joinToString函数会把字面量直接变成字符串，比如说[64,0]会变成"640"

            val index = block.piece.expandToByteInts().map { it.toChar() }.joinToString("")
            val offset = block.offset.expandToByteInts().map { it.toChar() }.joinToString("")
            val length = block.length.expandToByteInts().map { it.toChar() }.joinToString("")

            val payload = index + offset + length

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

    /**
     * Close the socket.
     */
    private suspend fun closeSocket() {
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

    /**
     * Tries to establish a new TCP connection with the peer by performing the following actions:
     *
     * 1. Sends a BitTorrent handshake message. Waits for its reply and compares the info hashes.
     * 2. Receives and stores the BitField from the peer.
     * 3. Send an Interested message to the peer.
     *
     * Returns true if a stable connection is successfully established, otherwise false.
     */
    private suspend fun establishNewConnection(): Boolean {
        return try {
            performHandshake()
            receiveBitField()
            sendInterested()
            true
        } catch (e: Exception) {
            Log.error { "An error occurred while connecting with peer [${peer.ip}]" }
            Log.error { e.message ?: e.cause.toString() }
            false
        }
    }

    /**
     * A wrapper function that calls the `recvData(channel:buffer:)` function. It converts the raw data into a BitTorrentMessage
     * if the ID could be read. Otherwise an error will be raised.
     */
    private suspend fun receiveMessage(bufferSize: Int = 0): BitTorrentMessage {
        val reply = recvData(inputChannel, bufferSize)
        if (reply.isEmpty()) {
            Log.info { "Received message 'keep alive' from peer [${peer.ip}]" }
            return BitTorrentMessage(MessageId.KEEP_ALIVE, reply)
        }
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
        Log.debug { "Received message with ID $messageId from peer [${peer.ip}]" }
        return BitTorrentMessage(messageId, payload)
    }

    /**
     * The main entry to launch the current worker. Must be wrapped in a launch scope to make it run in another coroutine.
     */
    suspend fun start() {
        Log.info { "Downloading thread started..." }
        while (!(terminated || pieceManager.isComplete())) {

            peer = peers.receive()

            // If a dummy peer is received, the worker will finish its job.
            // This is used to terminate all workers once the download is completed.
            if (peer.ip == DUMMY_PEER_IP)
                return

            try {
                // Establishes connection with the peer and lets it know that we are interested.
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
                            MessageId.KEEP_ALIVE -> {
                                Log.info { "Received keep_alive, waiting for next request attempt" }
                                for (i in 120 downTo 1) {
//                                    Log.debug { "waiting for two minutes, $i seconds remains" }
                                    delay(1000L)
                                }
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
                Log.error { e.message ?: e.cause.toString() }
            }
        }
    }

    /**
     * Terminates the worker by triggering the flag to true
     */
    fun stop() {
        terminated = true
    }

}