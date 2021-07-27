package moe.irony.peer_wire

import khttp.get
import moe.irony.bencode_decoder.*
import moe.irony.utils.bytesToInt
import moe.irony.utils.hexDecode
import java.io.File

data class Peer(val ip: String, val port: Int)

const val TRACKER_TIMEOUT = 15.000

class PeerRetriever(
    private val peerId: String,
    private val announceUrl: String,
    private val infoHash: String,
    val port: Int,
    val fileSize: Long,
) {

    private fun decodeResponse(resp: String): List<Peer> {
        println("Decoding tracker response...")

        val bencode = Decoder(resp).decode()
        val peerResp = bencode.convertToPeerResponse().unsafeGet()

        val peers = mutableListOf<Peer>()

        if (true) {
            val peerInfoSize = 6
            val peersString = peerResp.peers
            if (peersString.size % 6 != 0) {
                throw IllegalStateException("Received malformed 'peers' from tracker. [ 'peers' length needs to be divisible by 6 ]")
            }
            val peerNum = peersString.size / peerInfoSize

            for (i in 0 until peerNum) {
                val offset = i * peerInfoSize
                val ip = buildString {
                    append(peersString[offset])
                    append('.')
                    append(peersString[offset + 1])
                    append('.')
                    append(peersString[offset + 2])
                    append('.')
                    append(peersString[offset + 3])
                }
                val port = peersString.slice(offset + 4 until offset + 6).bytesToInt()
                peers.add(Peer(ip, port))
            }
        } else {
            throw NotImplementedError("The impl of non-compact case is unsupported")
        }

        println("Decode tracker response: SUCCESS")
        println("Number of peers discovered: ${peers.size}")

        return peers
    }

    fun retrievePeers(byteDownloaded: Long = 0L): List<Peer> {
        check(byteDownloaded >= 0) { "Downloaded bytes must be positive" }
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
            "info_hash" to infoHash.hexDecode(),
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
                println("Response > ")
                println(rsp.text)
                decodeResponse(rsp.text)
            }
            else -> {
                println("Retrieving response from tracker: FAILED [ ${rsp.statusCode}: ${rsp.text} ]")
                listOf()
            }
        }
    }
}

fun main() {
    val torrent = File("MoralPsychHandbook.pdf.torrent").readText(Charsets.US_ASCII)
    val bencode = Decoder(torrent).decode()

    val seed = bencode.convertToTorrentSeed()
    val file = seed.convertToTorrentFile()

    val realfile = file.unsafeGet()

    val peersRetriver = PeerRetriever(
        peerId = "-UT2021-114514191081",
        announceUrl = realfile.announce,
        infoHash = realfile.infoHash,
        port = 12450,
        fileSize = realfile.length
    )

    val li = peersRetriver.retrievePeers(0)
    li.forEach {
        println(it)
    }

    println("Convert finished")
}