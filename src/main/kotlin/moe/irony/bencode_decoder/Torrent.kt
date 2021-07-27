package moe.irony.bencode_decoder

import moe.irony.utils.fp.Result
import moe.irony.utils.hash
import java.io.File
import java.math.BigInteger

data class TorrentInfo(
    val pieces: String,
    val pieceLength: Long,
    val length: Long,
    val name: String,
) {

    val bencodePiece: String
        get() {
            val bytes = pieces.toByteArray()
            return "${bytes.size}:$bytes"
        }
}

data class TorrentSeed(
    val info: TorrentInfo,
    val announce: String,
    val infoHash: String,
)

data class TorrentFile(
    val announce: String,
    val infoHash: String,
    val pieceHashes: List<String>,
    val pieceLength: Long,
    val length: Long,
    val name: String,
)

fun DictionaryLiteral.getIntAttr(attr: String): Result<BigInteger> = this.of[ByteStringLiteral(attr)]?.let {
    when (it) {
        is IntLiteral -> Result(it.value)
        else -> null
    }
} ?: Result.failure("Cannot get IntAttr $attr from dictionary")

fun DictionaryLiteral.getStringAttr(attr: String): Result<String> = this.of[ByteStringLiteral(attr)]?.let {
    when (it) {
        is ByteStringLiteral -> Result(it.content)
        else -> null
    }
} ?: Result.failure("Cannot get StringAttr $attr from dictionary")

fun DictionaryLiteral.getDictAttr(attr: String): Result<DictionaryLiteral> = this.of[ByteStringLiteral(attr)]?.let {
    when (it) {
        is DictionaryLiteral -> Result(it)
        else -> null
    }
} ?: Result.failure("Cannot get DictAttr $attr from dictionary")

fun Result<DictionaryLiteral>.getInfo(): Result<TorrentInfo> =
    this.flatMap { dict ->
        dict.getIntAttr("length").flatMap { length ->
            dict.getStringAttr("name").flatMap { name ->
                dict.getIntAttr("piece length").flatMap { pieceLength ->
                    dict.getStringAttr("pieces").flatMap { pieces ->
                        Result(TorrentInfo(length = length.toLong(), name = name, pieceLength = pieceLength.toLong(), pieces = pieces))
                    }
                }
            }
        }
    }.mapFailure("Encountered error while trying to parse to info")

fun Result<DictionaryLiteral>.getSeed(): Result<TorrentSeed> =
    this.flatMap { dict ->
        dict.getStringAttr("announce").flatMap { ann ->
            dict.getDictAttr("info").flatMap { info ->
                Result(info).getInfo().flatMap { infos ->
                    Result(info).getInfoHash().flatMap { ih ->
                        Result(TorrentSeed(info = infos, announce = ann, infoHash = ih))
                    }
                }
            }
        }
    }.mapFailure("Encountered error while trying to parse to seed")

fun Result<Bencode>.convertToTorrentSeed(): Result<TorrentSeed> =
    this.flatMap {
        when (it) {
            is DictionaryLiteral -> Result(it).getSeed()
            else -> Result.failure("Wrong type, not a torrent seed")
        }
    }.mapFailure("Encountered error while trying to convert the bencode to seed")

fun Result<DictionaryLiteral>.getInfoHash(): Result<String> =
    this.flatMap { info ->
        Result(info.encode().hash())
    }.mapFailure("Encountered error while trying to hash the info dictionary")

fun Result<TorrentSeed>.getPieceHashes(): Result<List<String>> =
    this.flatMap { seed ->
        val hashes = seed.info.pieces.toCharArray().toList()
        check (hashes.size % 20 == 0)
        Result(hashes.chunked(20).map { it.joinToString("") })
    }.mapFailure("Encountered error while trying to split piece into hashes")

fun Result<TorrentSeed>.convertToTorrentFile(): Result<TorrentFile> =
    this.flatMap { seed ->
        this.getPieceHashes().flatMap { pieceHash ->
            Result(TorrentFile(
                seed.announce, seed.infoHash, pieceHash, seed.info.pieceLength, seed.info.length, seed.info.name))
        }
    }

fun main() {
    val message = File("MoralPsychHandbook.pdf.torrent").readText(Charsets.US_ASCII)

    val result = Decoder(message).decode()

    val torrent = result.convertToTorrentSeed().convertToTorrentFile()

    val real = torrent.unsafeGet()

    val infohash = real.infoHash
    val pieceshash = real.pieceHashes

    println(infohash)
    println(pieceshash)

    println(infohash.map { it.code.toChar() }.joinToString(""))
    println(pieceshash[0].map { it.code.toChar()}.joinToString(""))

}