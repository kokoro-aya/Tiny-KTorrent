package moe.irony.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.HttpTimeout
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.irony.bencode_decoder.Peer
import moe.irony.bencode_decoder.TorrentFileParser
import moe.irony.connect.Block
import moe.irony.peer_wire.PeerRetriever
import moe.irony.pieces.PieceManager
import moe.irony.utils.fp.iterate
import moe.irony.workers.Worker
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.random.Random

const val PORT = 6881
const val PEER_QUERY_INTERVAL = 90_000

class TorrentClient(
    private val workersNum: Int = 8,
    enableLogging: Boolean = true,
    logFilePath: String = "logs/client.log"
) {
    private val peerId: String
    private val peerQueue: ConcurrentLinkedDeque<Peer>

    private val workers: MutableList<Worker> = mutableListOf()

    init {
        peerId = "-UT2021-" +
                iterate(0, { Random(it).nextInt(0, 9) }, 12).joinToString("")

        peerQueue = ConcurrentLinkedDeque()

        if (enableLogging) {
            // TODO
        } else {
            // TODO
        }
    }

    fun terminate() {
        peerQueue.addAll(List(workersNum) { Peer("0.0.0.0", "", 0) })
        workers.forEach(Worker::stop)
    }

    suspend fun downloadFile(torrentFilePath: String, downloadDirectory: String) {
        println("Parsing Torrent file $torrentFilePath ...")

        val torrent = TorrentFileParser(torrentFilePath).torrent

        val announceUrl = torrent.announce
        val fileSize = torrent.length
        val infoHash = torrent.infoHash
        val fileName = torrent.name

        val downloadPath = downloadDirectory + fileName

        val pieceManager = PieceManager(torrent, downloadPath, workersNum)

        val client = HttpClient(CIO) {
            install(HttpTimeout)
        }

        peerQueue.addAll(
            PeerRetriever(peerId, announceUrl, infoHash, PORT, fileSize, client)
                .retrievePeers(pieceManager.bytesDownloaded())
        )

        workers.addAll((1 .. workersNum)
            .map { Worker(peerQueue, peerId, infoHash, pieceManager) }
            .onEach {
                it.start()
            })
            .also {
                println("Download intialized...")
            }

        var lastPeerQuery = System.currentTimeMillis()

        while (!pieceManager.isComplete()) {
            val diff = System.currentTimeMillis() - lastPeerQuery
            if (diff >= PEER_QUERY_INTERVAL || peerQueue.isEmpty()) {
                val peerRetriever = PeerRetriever(peerId, announceUrl, infoHash, PORT, fileSize, client)
                val peers = peerRetriever.retrievePeers(pieceManager.bytesDownloaded())
                if (!peerQueue.isEmpty()) {
                    peerQueue.clear()
                    peerQueue.addAll(peers)
                }
                lastPeerQuery = System.currentTimeMillis()
            }
        }

        terminate()

        println("Download completed!")
        println("File downloaded to $downloadPath")
    }
}