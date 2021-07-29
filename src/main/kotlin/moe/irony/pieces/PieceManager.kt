package moe.irony.pieces

import moe.irony.bencode_decoder.TorrentFile
import moe.irony.connect.Block
import moe.irony.connect.BlockStatus
import moe.irony.utils.fp.Option
import moe.irony.utils.fp.unfold
import moe.irony.utils.hasPiece
import moe.irony.utils.setPiece
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

const val BLOCK_SIZE = 16384L
const val MAX_PENDING_TIME = 5_000
const val PROGRESS_BAR_WIDTH = 80
const val PROGRESS_DISPLAY_INTERVAL = 500

data class PendingRequest(
    val block: Block,
    val timestamp: Long,
)

class PieceManager(
    private val torrentFile: TorrentFile,
    downloadPath: String,
    private val maximumConnections: Int
) {

    private val peers: ConcurrentMap<String, ByteArray> = ConcurrentHashMap()
    private val allPieces: List<Piece>

    private val missingPieces = ConcurrentLinkedDeque<AtomicReference<Piece>>()
    private val onGoingPieces = ConcurrentLinkedDeque<AtomicReference<Piece>>()
    private val finishedPieces = ConcurrentLinkedDeque<String>()

    private val pendingRequests = ConcurrentLinkedDeque<AtomicReference<PendingRequest>>()

    private val startingTime: Long
    private val totalPieces = torrentFile.pieceHashes.size
    private val pieceLength = torrentFile.pieceLength

    private val downloadedFile: RandomAccessFile

    init {
        allPieces = initiatePieces()
        missingPieces.addAll(allPieces.map { AtomicReference(it) })

        val file = File(downloadPath)
        val raf = RandomAccessFile(file, "rw")
        raf.setLength(torrentFile.length)

        downloadedFile = raf

        startingTime = System.currentTimeMillis()


    }

    private fun Long.expandWithRem(divideBy: Long): List<Long> = unfold(this) {
        if (it < 0)
            null
        else
            min(it, divideBy) to it - divideBy
    }

    private fun initiatePieces(): List<Piece> {
        // totalPieces:                         总共有多少个piece
        val totalLength = torrentFile.length // 文件的总大小
        // pieceLength:                         单个分片的大小（除了最后一piece）

        val piecesLength = totalLength.expandWithRem(pieceLength) // 每个分片的大小

        check (piecesLength.size == totalPieces) { "Check failed :: the torrent must have $totalPieces pieces but got initialized ${piecesLength.size} pieces" }

        val blocksCounts = piecesLength.map { if (it % BLOCK_SIZE == 0L) (it / BLOCK_SIZE).toInt() else (it / BLOCK_SIZE + 1).toInt() }

        val blocksLengths = piecesLength.map { it.expandWithRem(BLOCK_SIZE) }

        check ( blocksCounts.mapIndexed { i, it -> blocksLengths[i].size == it }.all { true } ) {
            "Check failed :: A piece may have block count that's differ from the count calculated"
        }

        // blocksCounts -> 每个分片有多少个Block
        // blocksLengths -> 每个分片里每个Block的大小
        return blocksCounts.zip(blocksLengths).mapIndexed { ip, (_, thisLength) ->
            val thisBlocks = thisLength.mapIndexed { ib, blen ->
                Block(ip, (ib * BLOCK_SIZE).toInt(), blen.toInt(), BlockStatus.MISSING, "")
            }
            Piece(torrentFile.pieceHashes[ip], ip, thisBlocks)
        }
    }

    private fun expiredRequest(peerId: String): Block? {
        val currentTime = System.currentTimeMillis()
        return peers[peerId] ?.let { ba ->
            pendingRequests
                .map { it.get() }
                .filter { ba.hasPiece(it.block.piece) }
                .firstOrNull { currentTime - it.timestamp >= MAX_PENDING_TIME }
                ?.also {
                    println("Block ${it.block.offset} from piece ${it.block.piece} has expired.")
                }?.block
        }
    }

    private fun nextOngoing(peerId: String): Block? {
        return peers[peerId] ?.let { ba ->
            onGoingPieces.map { it.get() }
                .firstOrNull { ba.hasPiece(it.index) }
                ?.nextRequest()
                ?.also {
                    val currentTime = System.currentTimeMillis()
                    val newPendingRequest = PendingRequest(block = it, timestamp = currentTime)
                    pendingRequests.add(AtomicReference(newPendingRequest))
                }
        }
    }

    private fun ConcurrentLinkedDeque<AtomicReference<Piece>>.getRarestPiece(): AtomicReference<Piece>? {
        val rarest = this.map {
            val piece = it
            val peerFields = peers.values
            piece to peerFields.filter { it.hasPiece(piece.get().index) }.size
        }
            .filter { it.second > 0 } // 如果没有任何peer有资源的话就无法下载
            .sortedWith { o1, o2 -> o2.second - o1.second }.firstOrNull()?.first
        if (rarest != null)
            this.remove(rarest)
        return rarest
    }

    private fun Piece.writeToFile() {
        val pos = this.index * torrentFile.pieceLength
        val data = this.getData().map { it.code.toByte() }.toByteArray()
        val len = data.size
        downloadedFile.write(data, pos.toInt(), len)
    }

    private fun displayProgressBar() {
        // TODO()
    }

    private fun trackProgress() {
        // TODO()
    }

    val isComplete: Boolean
        get() = finishedPieces.size == totalPieces

    fun blockReceived(peerId: String, pieceIndex: Int, blockOffset: Int, data: String) {
        println("Received block $blockOffset from piece $pieceIndex from peer $peerId")
        val removedRequest = pendingRequests.firstOrNull {
            val bl = it.get().block
            bl.piece == pieceIndex && bl.offset == blockOffset
        } ?.also {
            pendingRequests.remove(it)
        } ?: throw IllegalStateException("Received a block that's not in pendingRequest")
        // TODO()
    }

    fun addPeer(peerId: String, bitField: String) {
        if (peers.size < maximumConnections) {
            peers[peerId] = bitField.map { it.code.toByte() }.toByteArray() // 这里又会出现问题么
            println("Number of connections: ${peers.size} / $maximumConnections")
        }
    }

    fun removePeer(peerId: String) {
        if (isComplete) return
        when (peers.containsKey(peerId)) {
            true -> {
                peers -= peerId
                println("Number of connections: ${peers.size} / $maximumConnections")
            }
            else -> throw IllegalStateException("Attempting to remove a peer $peerId with whom a connection has not been established")
        }
    }

    fun updatePeer(peerId: String, index: Int) {
        if (peers.containsKey(peerId)) {
            peers[peerId]!!.setPiece(index)
        } else {
            throw IllegalStateException("Connection has not been established with peer $peerId")
        }
    }

    val bytesDownloaded: Long
        get() = finishedPieces.size * pieceLength


    fun nextRequest(peerId: String): Block? {
        fun missingPiecesEmpty(): Option<ConcurrentLinkedDeque<AtomicReference<Piece>>> = when (missingPieces.isNotEmpty()) {
            true -> Option.Some(missingPieces)
            else -> Option.None
        }
        fun peersContainsIdEmpty(): Option<String> = when (peers.containsKey(peerId)) {
            true -> Option.Some(peerId)
            else -> Option.None
        }
        fun expiredRequestOpt(): Option<Block> = when (val er = expiredRequest(peerId)) {
            null -> Option.None
            else -> Option.Some(er)
        }
        fun nextOngoingOpt(): Option<Block> = when (val no = nextOngoing(peerId)) {
            null -> Option.None
            else -> Option.Some(no)
        }
        fun getRarestPieceOpt(missingPieces: Option<ConcurrentLinkedDeque<AtomicReference<Piece>>>): Option<Block> =
            missingPieces
                .flatMap { Option.toOption(it.getRarestPiece()?.get()?.nextRequest()) }

        return missingPiecesEmpty().flatMap { cld ->
            peersContainsIdEmpty().flatMap {
                expiredRequestOpt().mapNone(::nextOngoingOpt).mapNone { getRarestPieceOpt(Option.Some(cld)) }
            }
        }.toNullable()
    }
}