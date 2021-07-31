package moe.irony.bencode_decoder

import moe.irony.utils.Log
import java.io.File

class TorrentFileParser(
    filePath: String
) {
    val torrent: TorrentFile

    init {
        Log.info { "Parsing Torrent file $filePath ..." }
        val file = File(filePath).readBytes()
            .map { it.toChar() }
            .joinToString("")

        val bencode = Decoder(file).decode()

        torrent = bencode.convertToTorrentSeed().convertToTorrentFile().unsafeGet()

        Log.info { "Parse Torrent file: SUCCESS" }
    }
}