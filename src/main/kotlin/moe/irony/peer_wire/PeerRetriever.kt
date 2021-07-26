package moe.irony.peer_wire

import khttp.get
import moe.irony.bencode_decoder.InfoHash
import moe.irony.bencode_decoder.TorrentFile
import moe.irony.utils.hexDecode
import moe.irony.utils.stringRepr

data class Peer(val ip: String, val port: Int)

const val TRACKER_TIMEOUT = 15.000

class PeerRetriever(
    private val peerId: String,
    private val announceUrl: String,
    private val infoHash: InfoHash,
    val port: Int,
    val fileSize: ULong,
) {

    private fun decodeResponse(resp: String): List<Peer> {
        println("Decoding tracker response...")

        throw NotImplementedError(TODO())
    }

    fun retrievePeers(byteDownloaded: ULong = 0u): List<Peer> {
        buildString {
            appendLine("Retrieving peers from $announceUrl with the following parameters")
            appendLine("info_hash: $infoHash")
            appendLine("peer_id: $peerId")
            appendLine("port: $port")
            appendLine("uploaded: 0")
            appendLine("downloaded: $byteDownloaded")
            appendLine("left: ${fileSize - byteDownloaded}")
            appendLine("compact: 1")
        }

        val rsp = get(url = announceUrl, params = mapOf(
            "info_hash" to infoHash.stringRepr.hexDecode(),
            "peer_id" to peerId,
            "port" to port.toString(),
            "uploaded" to "0",
            "downloaded" to byteDownloaded.toString(),
            "left" to "${fileSize - byteDownloaded}",
            "compact" to "1",
        ), timeout = TRACKER_TIMEOUT )

        return when (rsp.statusCode) {
            200 -> {
                println("Retrieve response from tracker: SUCCESS")
                decodeResponse(rsp.text)
            }
            else -> {
                println("Retrieving response from tracker: FAILED [ ${rsp.statusCode}: ${rsp.text} ]")
                listOf()
            }
        }
    }
}