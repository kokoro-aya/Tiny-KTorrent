package moe.irony.pieces

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import moe.irony.bencode_decoder.TorrentFile
import moe.irony.connect.Block
import moe.irony.connect.BlockStatus
import moe.irony.utils.Log
import moe.irony.utils.formatTime
import moe.irony.utils.fp.Option
import moe.irony.utils.fp.unfold
import moe.irony.utils.hasPiece
import moe.irony.utils.setPiece
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow

const val BLOCK_SIZE = 16384L
const val MAX_PENDING_TIME = 10_000
const val PROGRESS_BAR_WIDTH = 80
const val PROGRESS_DISPLAY_INTERVAL = 500

/**
 * Represents a request that's pending in the queue
 */
data class PendingRequest(
    val block: Block,
    val timestamp: Long,
)

/**
 * The class that manages the torrent download process. Its main roles are
 * - Create all Piece objects and store them
 * - Add, update, remove peer's ID and BitField when an action is called
 * - Store all downloaded block, check if the piece is finished and then check if the sha1 hash
 *   matches, then write the downloaded piece into disk
 * - Get the next block to be downloaded by using the rarest first algorithm
 * - Check if the download is completed.
 */
class PieceManager(
    private val torrentFile: TorrentFile,
    downloadPath: String,
    private val maximumConnections: Int
) {

    private val mutex = Mutex() // Must use a Mutex instead of AtomicReference as we will call methods on refs.

    private val peers: ConcurrentMap<String, ByteArray> = ConcurrentHashMap()
    private val allPieces: List<Piece>

    private val missingPieces = ConcurrentLinkedDeque<Piece>()
    private val onGoingPieces = ConcurrentLinkedDeque<Piece>()
    private val finishedPieces = ConcurrentLinkedDeque<String>()

    private val pendingRequests = ConcurrentLinkedDeque<PendingRequest>()

    private val startingTime: Long
    private val totalPieces = torrentFile.pieceHashes.size
    private val pieceLength = torrentFile.pieceLength

    private var piecesDownloadedInInterval: Int = 0

    // Use RandomAccessFile for writing to a sparse data.
    private val downloadedFile: RandomAccessFile

    init {
        allPieces = initiatePieces()
        missingPieces.addAll(allPieces.map { it })

        val file = File(downloadPath)
        val raf = RandomAccessFile(file, "rw")
        raf.setLength(torrentFile.length)

        downloadedFile = raf

        startingTime = System.currentTimeMillis()

    }

    /**
     * Expands a number to a list of numbers that contains every dividends and the last as the remainder.
     * For example, with a divideBy = 5, 17 will be expanded to [5,5,5,2].
     * If the remainder is 0, then the last element will not be expanded.
     */
    private fun Long.expandWithRem(divideBy: Long): List<Long> = unfold(this) {
        if (it <= 0) // 这里必须小于等于零，不然会多一个零元素
            null
        else
            min(it, divideBy) to it - divideBy
    }

    /**
     * Pre-constructs the list of pieces and blocks based on the number of pieces and the size of the block.
     * @return a list containing all pieces in the file
     */
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

    /**
     * Goes through previously requested blocks, if one has been in the requested state for longer than
     * `MAX_PENDING_TIME` then returns the block to be re-requested. If there is no such Block, null will be returned
     */
    private fun expiredRequest(peerId: String): Block? {
        val currentTime = System.currentTimeMillis()
        return peers[peerId] ?.let { ba ->
            pendingRequests
                .filter { ba.hasPiece(it.block.piece) }
                .firstOrNull { currentTime - it.timestamp >= MAX_PENDING_TIME }
                ?.also {
                    Log.info { "Block ${it.block.offset} from piece ${it.block.piece} has expired." }
                }?.block
        }
    }

    /**
     * Iterates through the pieces that are currently being downloaded, and returns the next Block to be
     * requested or null if no such block is left from the list of Pieces
     */
    private fun nextOngoing(peerId: String): Block? {
        return peers[peerId] ?.let { ba ->
            onGoingPieces
                .firstOrNull { ba.hasPiece(it.index) }
                ?.nextRequest()
                ?.also {
                    val currentTime = System.currentTimeMillis()
                    val newPendingRequest = PendingRequest(block = it, timestamp = currentTime)
                    pendingRequests.add(newPendingRequest)
                }
        }
    }

    /**
     * Given the list of missing pieces, finds the rarest one (that is owned by fewest number of peers)
     */
    private fun ConcurrentLinkedDeque<Piece>.getRarestPiece(): Piece? {
        val rarest = this.map {
            val piece = it
            val peerFields = peers.values
            piece to peerFields.filter { it.hasPiece(piece.index) }.size
        }
            .filter { it.second > 0 } // 如果没有任何peer有资源的话就无法下载
            .sortedWith { o1, o2 -> o2.second - o1.second }.firstOrNull()?.first
        if (rarest != null) {
            this.remove(rarest)
            onGoingPieces.add(rarest)
        }
        return rarest
    }

    /**
     * Writes the given Piece to disk
     */
    private fun Piece.writeToFile() {
        val pos = this.index * torrentFile.pieceLength
        val data = this.getData().map { it.code.toByte() }.toByteArray()
        val len = data.size
        downloadedFile.seek(pos)
        downloadedFile.write(data, 0, len) // 这里的offset是data的offset，不是file的offset
    }

    /**
     * Creates and outputs a progress bar in console.
     */
    private suspend fun displayProgressBar() {
        mutex.withLock {
            val downloadedPieces = finishedPieces.size
            val downloadedLength = pieceLength * piecesDownloadedInInterval

            val avgDownloadSpeed = downloadedLength.toDouble() / PROGRESS_DISPLAY_INTERVAL.toDouble()
            val avgDownloadSpeedInMBS = avgDownloadSpeed / (2.0).pow(20)

            val timePerPiece = PROGRESS_DISPLAY_INTERVAL.toDouble() / piecesDownloadedInInterval.toDouble()
            val remainingTime = ceil(timePerPiece * (totalPieces - downloadedPieces)).toLong()

            val progress = downloadedPieces.toDouble() / totalPieces.toDouble()
            val pos = (PROGRESS_BAR_WIDTH * progress).toInt()

            val currentTime = System.currentTimeMillis()
            val timeSinceStart = currentTime - startingTime

            val output = buildString {
                append("[Peers: ${peers.size} / $maximumConnections, ")
                append("%.2f".format(avgDownloadSpeedInMBS))
                append(" MiB/s, ")
                append("ETA: ${remainingTime.formatTime()}]")
                appendLine()

                append("[")
                for (i in 0 until PROGRESS_BAR_WIDTH) {
                    append(
                        when {
                            i < pos -> "="
                            i == pos -> ">"
                            else -> " "
                        }
                    )
                }
                append("]")

                append("$downloadedPieces / $totalPieces")
                append("[${"%.2f".format(progress * 100)}]")

                append("in ${timeSinceStart.formatTime()}")
                appendLine()

                if (isComplete())
                    appendLine()
            }

            println(output)
        }
    }

    /**
     * A function used by the Progress coroutine to collect and calculate statistics collected
     * during the download and display them in the form of a progress bar.
     */
    suspend fun trackProgress() {
        delay(1000L)
        while (!isComplete()) {
            displayProgressBar()
            piecesDownloadedInInterval = 0
            delay(1000L)
        }
    }

    /**
     * Checks if all Pieces have been downloaded.
     * @return ture if all Pieces are present and otherwise false
     */
    suspend fun isComplete(): Boolean {
        return mutex.withLock {
            finishedPieces.size == totalPieces
        }
    }

    /**
     * This method is called when a block of data has been received succesfully.
     * Once an entire Piece has been received, a sha1 hash is computed on the Piece and then
     * be compared to that from the torrent file. If a mismatch is detected, all the blocks in
     * the Piece will be reset to Missing to be redownload. Otherwise, the data will be written
     * to the disk.
     */
    suspend fun blockReceived(peerId: String, pieceIndex: Int, blockOffset: Int, data: String) {
        Log.info { "Received block $blockOffset from piece $pieceIndex from peer $peerId" }

        // Removes the received block from pending requests
        mutex.lock()
        val removedRequest = pendingRequests.firstOrNull {
            val bl = it.block
            bl.piece == pieceIndex && bl.offset == blockOffset
        }?.also {
            pendingRequests.remove(it)
        } // 这里只是从pendingRequests里面清除掉如果存在一个对应的request，但是如果没有的话也不应该报错

        // Retrieves the Piece to which the Block belongs
        val targetPiece = onGoingPieces.firstOrNull { it.index == pieceIndex }
            ?: throw IllegalStateException("Received block does not belong to any ongoing piece")
        mutex.unlock()

        targetPiece.blockReceived(blockOffset, data)
        if (targetPiece.isComplete) {
            when (targetPiece.isHashMatching) {
                true -> { // Writes to the disk
                    targetPiece.writeToFile()

                    // Removes the Piece from the ongoing list
                    mutex.lock()
                    onGoingPieces.remove(targetPiece)
                    finishedPieces.add("P#${targetPiece.index}")
                    piecesDownloadedInInterval++
                    mutex.unlock()

                    buildString {
                        append("(${"%.2f".format(finishedPieces.size.toDouble() / totalPieces.toDouble() * 100)}%) ")
                        append("${finishedPieces.size} / $totalPieces Pieces downloaded...")
                        appendLine()
                    }.let { Log.info { it } }
                }
                false -> {
                    targetPiece.reset()
                    Log.info { "Hash mismatch for piece ${targetPiece.index}" }
                }
            }
        }
    }

    /**
     * Adds a peer and the BitField representing the pieces that the peer has.
     * Store the given info in the map `peers`.
     */
    suspend fun addPeer(peerId: String, bitField: String) {
        if (peers.size < maximumConnections) {
            mutex.withLock {
                peers[peerId] = bitField.map { it.code.toByte() }.toByteArray() // 这里又会出现问题么
            }
            Log.info { "Number of connections: ${peers.size} / $maximumConnections" }
        }
    }

    /**
     * Removes a previously added peer in case of lost of connection.
     * @param peerId ID of peer to be removed.
     */
    suspend fun removePeer(peerId: String) {
        if (isComplete()) return
        mutex.lock()
        when (peers.containsKey(peerId)) {
            true -> {
                peers -= peerId
                mutex.unlock()
                Log.info { "Number of connections: ${peers.size} / $maximumConnections" }
            }
            else -> {
                mutex.unlock()
                throw IllegalStateException("Attempting to remove a peer $peerId with whom a connection has not been established")
            }
        }
    }

    /**
     * Updates the info about which pieces a peer has (it corresponds to a `Have` message)
     */
    suspend fun updatePeer(peerId: String, index: Int) {
        mutex.lock()
        if (peers.containsKey(peerId)) {
            peers[peerId]!!.setPiece(index)
            mutex.unlock()
        } else {
            mutex.unlock()
            throw IllegalStateException("Connection has not been established with peer $peerId")
        }
    }

    /**
     * Calculates the number of bytes downloaded.
     */
    suspend fun bytesDownloaded(): Long {
        return mutex.withLock {
            finishedPieces.size * pieceLength
        }
    }

    /**
     * Retrieves the next block that should be requested from the given peer.
     * If there are no more block left or if the peer does not have any more missing piece, null is returned.
     * @return a nullable Block indicates the next Block to be requested from the peer.
     */
    suspend fun nextRequest(peerId: String): Block? {
        fun missingPiecesEmpty(): Option<ConcurrentLinkedDeque<Piece>> = when (missingPieces.isNotEmpty()) {
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
        fun getRarestPieceOpt(missingPieces: Option<ConcurrentLinkedDeque<Piece>>): Option<Block> =
            missingPieces
                .flatMap { Option.toOption(it.getRarestPiece()?.nextRequest()) }

        mutex.withLock {
            return missingPiecesEmpty().flatMap { cld ->
                peersContainsIdEmpty().flatMap {
                    expiredRequestOpt().mapNone(::nextOngoingOpt).mapNone { getRarestPieceOpt(Option.Some(cld)) }
                }
            }.toNullable()
        }
    }
}