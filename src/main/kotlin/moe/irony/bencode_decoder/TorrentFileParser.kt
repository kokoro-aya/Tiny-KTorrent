package moe.irony.bencode_decoder

import java.io.File

class TorrentFileParser(
    filePath: String
) {
    val torrent: TorrentFile

    init {
        println("Parsing Torrent file $filePath ...")
        val file = File(filePath).readBytes()
            .map { it.toChar() }
            .joinToString("")

        val bencode = Decoder(file).decode()

        torrent = bencode.convertToTorrentSeed().convertToTorrentFile().unsafeGet()

        println("Parse Torrent file: SUCCESS")
    }
}