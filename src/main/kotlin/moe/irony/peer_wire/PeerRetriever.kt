package moe.irony.peer_wire

import io.ktor.client.HttpClient
import io.ktor.client.call.receive
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.HttpTimeout
import io.ktor.client.features.defaultRequest
import io.ktor.client.features.get
import io.ktor.client.features.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.port
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.UrlEncodingOption
import kotlinx.coroutines.runBlocking
import moe.irony.bencode_decoder.*
import moe.irony.utils.Log
import moe.irony.utils.bytesToInt
import moe.irony.utils.hexToUrlEncode
import java.io.File

const val TRACKER_TIMEOUT = 30_000L

/**
 * A PeerRetriever get all info from a torrent, tries to connect to the tracker to retrieve a list of peers from it.
 * @param peerId the peer ID of this BitTorrent client
 * @param announceUrl the HTTP URL to the tracker
 * @param infoHash the info hash of the torrent file
 * @param port the TCP port that this client is listening on
 * @param fileSize the size of the file to be downloaded, in bytes
 * @param client A Ktor HttpClient instance
 */
class PeerRetriever(
    private val peerId: String,
    private val announceUrl: String,
    private val infoHash: String,
    val port: Int,
    val fileSize: Long,
    val client: HttpClient,
) {


    /**
     * Decodes the response string sent by the tracker. If the string can be successfully decoded,
     * @return a pair of a list of Peers to the interval.
     */
    private fun decodeResponse(resp: String): Pair<List<Peer>, Int> {
        Log.info { "Decoding tracker response..." }

        val bencode = Decoder(resp).decode()
        val nonCompact = bencode.convertToPeerResponse()
        if (nonCompact.isSuccess()) {
            val peerResp = nonCompact.unsafeGet()

            // Handles the case where peer information is stored in a list
            val peers = peerResp.peers
            val interval = peerResp.interval * 1_000 // interval is represented in seconds

            Log.info { "Decode tracker response: SUCCESS" }
            Log.info { "Number of peers discovered: ${peers.size}" }

            return peers to interval
        } else {
            // TODO: Handle two cases whether the received info is in binary blob (compact) or not

            val compact = bencode.convertToCompactResponse()
            val peerResp = compact.unsafeGet()

            val peers = mutableListOf<Peer>()
            val interval = peerResp.interval
            // Deserializing the peer information:
            // Detailed explanation can be found here:
            // https://blog.jse.li/posts/torrent/
            // Essentially, every 6 bytes represent a single peer with the first 4 bytes being the IP and the last
            // 2 bytes being the port number
            val peerInfoSize = 6
            val peersString = peerResp.peerInfos
            if (peersString.length % 6 != 0) {
                throw IllegalStateException("Received malformed 'peers' from tracker. [ 'peers' length needs to be divisible by 6 ]")
            }
            val peerNum = peersString.length / peerInfoSize

            for (i in 0 until peerNum) {
                val offset = i * peerInfoSize
                val ip = buildString {
                    append(peersString[offset].toByte().toUByte().toInt())
                    append('.')
                    append(peersString[offset + 1].toByte().toUByte().toInt())
                    append('.')
                    append(peersString[offset + 2].toByte().toUByte().toInt())
                    append('.')
                    append(peersString[offset + 3].toByte().toUByte().toInt())
                }
                val port = peersString.slice(offset + 4 until offset + 6).map { it.toByte().toUByte() }.bytesToInt()
                peers.add(Peer(ip, peersString.slice(offset until offset + 6), port))
            }
            return peers to interval
        }
    }

    /**
     * Retrieves the list of peers from the URL specified by the 'announce' property.
     * The list of parameters are the followings:
     * (source: https://zhuanlan.zhihu.com/p/386437665)
     * - info_hash: the sha1 hash of the info dict found in the .torrent file
     * - peer_id: a unique ID generated for this client
     * - uploaded: the total number of bytes uploaded
     * - downloaded: the total number of bytes downloaded
     * - left: the number of bytes left to download for this client
     * - port: the TCP port this client is listening on
     * - compact: whether or not the client accepts a compacted list of peers
     * Besides, the interval that a client should wait before refreshing its peers is collected.
     * @return a pair of a list of peers to the interval.
     */
    suspend fun retrievePeers(byteDownloaded: Long = 0L): Pair<List<Peer>, Int> {
        check(byteDownloaded >= 0) { "Downloaded bytes must be positive" }
        buildString {
            appendLine("Retrieving peers from $announceUrl with the following parameters")
            appendLine("info_hash: $infoHash")
            appendLine("info_hash_encoded: ${infoHash.hexToUrlEncode()}")
            // Note that info hash wil be url-encoded
            appendLine("peer_id: $peerId")
            appendLine("port: $port")
            appendLine("uploaded: 0")
            appendLine("downloaded: $byteDownloaded")
            appendLine("left: ${fileSize - byteDownloaded}")
            appendLine("compact: 0") // 不知为何返回的永远是不compact的，所以就改为0吧
        }.let { Log.info { it } }

        val rsp = client.get<HttpResponse>(urlString = announceUrl) {
            url {
                parameters.urlEncodingOption = UrlEncodingOption.NO_ENCODING // 关闭把parameter自动urlencode的机制
                parameters.append("info_hash", infoHash.hexToUrlEncode())
                parameters.append("peer_id", peerId)
                parameters.append("port", this@PeerRetriever.port.toString()) // 这里的port会和ktor内部的参数clash
                parameters.append("uploaded", "0")
                parameters.append("downloaded", byteDownloaded.toString())
                parameters.append("left", "${fileSize - byteDownloaded}")
                parameters.append("compact", "0")

            }
            timeout {
                requestTimeoutMillis = TRACKER_TIMEOUT
            }
        }

        val recv = rsp.receive<ByteArray>().map { it.toChar() }.joinToString("")

        return when (rsp.status) {
            // If response successfully retrieved
            HttpStatusCode.OK -> {
                Log.info { "Retrieve response from tracker: SUCCESS" }
//                println("Response > ")
//                println(recv)
                decodeResponse(recv)
            }
            else -> {
//                println("Retrieving response from tracker: FAILED")
//                println("Status > ${rsp.status}")
//                println("Message > $recv")
                Log.info { "Retrieving response from tracker: FAILED [ ${rsp.status}: $recv ]" }
                listOf<Peer>() to Int.MAX_VALUE
            }
        }
    }
}

fun main() {
    val torrent = File("MoralPsychHandbook.pdf.torrent").readBytes()
        .map { it.toChar() }
        .joinToString("")
    val bencode = Decoder(torrent).decode()

    val seed = bencode.convertToTorrentSeed()
    val file = seed.convertToTorrentFile()

    val realfile = file.unsafeGet()

//    println(realfile.infoHash)
//    println(realfile.infoHash.hexToUrlEncode())

    val client = HttpClient(CIO) {
        install(HttpTimeout)
    }

    val peersRetriver = PeerRetriever(
        peerId = "-UT2021-114514191081",
        announceUrl = realfile.announce,
        infoHash = realfile.infoHash,
        port = 6881,
        fileSize = realfile.length,
        client = client,
    )

    runBlocking {
        val li = peersRetriver.retrievePeers(0)
        li.first.forEach {
            println(it)
        }
    }

    println("Convert finished")
}

// khttp的坑，%号自己会被再encode一次