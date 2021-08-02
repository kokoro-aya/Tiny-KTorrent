package moe.irony.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.HttpTimeout
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import moe.irony.bencode_decoder.Peer
import moe.irony.bencode_decoder.TorrentFileParser
import moe.irony.connect.Block
import moe.irony.peer_wire.PeerRetriever
import moe.irony.pieces.PieceManager
import moe.irony.utils.Log
import moe.irony.utils.fp.iterate
import moe.irony.workers.Worker
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.random.Random

const val PORT = 6881
const val PEER_QUERY_INTERVAL = 90_000

/**
 * The client API interface that's exposed to the Main entrypoint
 * @param workersNum how many workers do we want for this file?
 * @param enableLogging do we want to log info for the debug purpose?
 */
class TorrentClient(
    private val workersNum: Int = 8,
    enableLogging: Boolean = true,
) {
    /**
     * the unique identifier of our client
     */
    private val peerId: String
    private val peers: Channel<Peer> // we use a channel instead of a shared queue as they acts the same behavior

    private val workers: MutableList<Worker> = mutableListOf()

    init {
        /* Generate a random 20-byte peer ID for the client as described in the convention
         * https://wiki.theory.org/BitTorrentSpecification#peer_id
         */
        peerId = "-UT2021-" + // we use the `iterate` function along with Random.nextInt() to generate a unique ID
                iterate(Random.nextInt(0, 9), { Random.nextInt(0, 9) }, 12).joinToString("")

        peers = Channel(capacity = Channel.UNLIMITED) // The channel should be unlimited as we don't want to block the `send()` actions

        // Initialize if the logger is enabled
        if (enableLogging) {
            Log.enableLog()
        }
    }

    suspend fun terminate() {
        List(workersNum) { Peer("0.0.0.0", "DUMMY", 0) }
            .forEach { peers.send(it) }
        workers.forEach(Worker::stop)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    /**
     * Download the file as per the content of the given Torrent file
     * @param torrentFilePath path to the torrent file
     * @param downloadDirectory destination directory
     */
    suspend fun downloadFile(torrentFilePath: String, downloadDirectory: String) {
        println("Parsing Torrent file $torrentFilePath ...")

        // Parse the torrent file
        val torrent = TorrentFileParser(torrentFilePath).torrent

        val announceUrl = torrent.announce
        val fileSize = torrent.length
        val infoHash = torrent.infoHash
        val fileName = torrent.name

        val downloadPath = downloadDirectory + fileName

        val pieceManager = PieceManager(torrent, downloadPath, workersNum)

        // Install the Ktor client
        val client = HttpClient(CIO) {
            install(HttpTimeout)
        }

        var interval: Int = PEER_QUERY_INTERVAL

        // First retrieve of peers before launching coroutines
        PeerRetriever(peerId, announceUrl, infoHash, PORT, fileSize, client)
            .retrievePeers(pieceManager.bytesDownloaded())
            .let {
                it.first.forEach { peers.send(it) }
                check (it.second > 0) { "Interval must be a positive number" }
                interval = it.second
            }

        if (peers.isEmpty) {
            Log.error { "No peer is available for this seed, download aborted" }
            return
        }

        // Launch the sub coroutine to print progress in console
        CoroutineScope(Dispatchers.Default).launch {
            pieceManager.trackProgress()
        }

        // Launch worker coroutines to do the job
        workers.addAll((1 .. workersNum)
            .map { Worker(peers, peerId, infoHash, pieceManager) }
            .onEach {
                CoroutineScope(Dispatchers.IO).launch {
                    it.start()
                }
            })
            .also {
                println("Download intialized...")
            }

        var lastPeerQuery = System.currentTimeMillis()

        while (!pieceManager.isComplete()) {

            val diff = System.currentTimeMillis() - lastPeerQuery
            if (diff >= interval || peers.isEmpty) {
                val peerRetriever = PeerRetriever(peerId, announceUrl, infoHash, PORT, fileSize, client)
                val (retrievedPeers, newInterval) = peerRetriever.retrievePeers(pieceManager.bytesDownloaded())
                if (retrievedPeers.isNotEmpty()) {
                    val removal = CoroutineScope(Dispatchers.Default).launch {
                        while (!peers.isEmpty)
                            peers.receive()
                    }
                    removal.join()
                    retrievedPeers.forEach {
                        peers.send(it)
                    }
                    interval = newInterval
                }
                lastPeerQuery = System.currentTimeMillis()
            }
        }

        terminate()

        println("Download completed!")
        println("File downloaded to $downloadPath")
    }
}