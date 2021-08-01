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

class PeerRetriever(
    private val peerId: String,
    private val announceUrl: String,
    private val infoHash: String,
    val port: Int,
    val fileSize: Long,
    val client: HttpClient,
) {

    private fun decodeResponse(resp: String): Pair<List<Peer>, Int> {
        Log.info { "Decoding tracker response..." }

        val bencode = Decoder(resp).decode()
        val peerResp = bencode.convertToPeerResponse().unsafeGet()

        val peers = peerResp.peers
        val interval = peerResp.interval

//        if (true) {
//            val peerInfoSize = 6
//            val peersString = peerResp.peers
//            if (peersString.size % 6 != 0) {
//                throw IllegalStateException("Received malformed 'peers' from tracker. [ 'peers' length needs to be divisible by 6 ]")
//            }
//            val peerNum = peersString.size / peerInfoSize
//
//            for (i in 0 until peerNum) {
//                val offset = i * peerInfoSize
//                val ip = buildString {
//                    append(peersString[offset])
//                    append('.')
//                    append(peersString[offset + 1])
//                    append('.')
//                    append(peersString[offset + 2])
//                    append('.')
//                    append(peersString[offset + 3])
//                }
//                val port = peersString.slice(offset + 4 until offset + 6).bytesToInt()
//                peers.add(Peer(ip, port))
//            }
//        } else {
//            throw NotImplementedError("The impl of non-compact case is unsupported")
//        }

        Log.info { "Decode tracker response: SUCCESS" }
        Log.info { "Number of peers discovered: ${peers.size}" }

        return peers to interval
    }

    suspend fun retrievePeers(byteDownloaded: Long = 0L): Pair<List<Peer>, Int> {
        check(byteDownloaded >= 0) { "Downloaded bytes must be positive" }
        buildString {
            appendLine("Retrieving peers from $announceUrl with the following parameters")
            appendLine("info_hash: $infoHash")
            appendLine("info_hash_encoded: ${infoHash.hexToUrlEncode()}")
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