package moe.irony.bencode_decoder

import moe.irony.utils.fp.Result
import moe.irony.utils.hash
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

    val bencodeRepresentation: String
        get() = "d6:lengthi${length}e4:name${name.length}:${name}12:piece lengthi${pieceLength}e6:pieces${bencodePiece}e"
}

data class TorrentSeed(
    val info: TorrentInfo,
    val announce: String,
)

typealias InfoHash = List<Byte>

data class TorrentFile(
    val announce: String,
    val infoHash: InfoHash,
    val pieceHashes: List<InfoHash>,
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
                    Result(TorrentSeed(info = infos, announce = ann))
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

fun Result<TorrentSeed>.getInfoHash(): Result<InfoHash> =
    this.flatMap { seed ->
        Result(seed.info.bencodeRepresentation.toByteArray().hash())
    }.mapFailure("Encountered error while trying to hash the info dictionary")

fun Result<TorrentSeed>.getPieceHashes(): Result<List<InfoHash>> =
    this.flatMap { seed ->
        val hashes = seed.info.pieces.toByteArray().toList()
        check (hashes.size % 20 == 0)
        Result(hashes.chunked(20))
    }.mapFailure("Encountered error while trying to split piece into hashes")

fun Result<TorrentSeed>.convertToTorrentFile(): Result<TorrentFile> =
    this.flatMap { seed ->
        this.getInfoHash().flatMap { infoHash ->
            this.getPieceHashes().flatMap { pieceHash ->
                Result(TorrentFile(
                    seed.announce, infoHash, pieceHash, seed.info.pieceLength, seed.info.length, seed.info.name))
            }
        }
    }

fun main() {
    val message = "d8:announce41:http://bttracker.debian.org:6969/announce7:comment35:" +
            "\"Debian CD from cdimage.debian.org\"13:creation datei1573903810e9:httpse" +
            "edsl145:https://cdimage.debian.org/cdimage/release/10.2.0//srv/cdbuilder." +
            "debian.org/dst/deb-cd/weekly-builds/amd64/iso-cd/debian-10.2.0-amd64-neti" +
            "nst.iso145:https://cdimage.debian.org/cdimage/archive/10.2.0//srv/cdbuild" +
            "er.debian.org/dst/deb-cd/weekly-builds/amd64/iso-cd/debian-10.2.0-amd64-n" +
            "etinst.isoe4:infod6:lengthi351272960e4:name31:debian-10.2.0-amd64-netinst" +
            ".iso12:piece lengthi262144e6:pieces20:abcdefghabcdefghabcdee"

    val result = Decoder(message).decode()

    val torrent = result.convertToTorrentSeed().convertToTorrentFile()

    val real = torrent.unsafeGet()

    val infohash = real.infoHash
    val pieceshash = real.pieceHashes

    println(infohash)
    println(pieceshash)

    println(infohash.map { it.toInt().toChar() }.joinToString(""))
    println(pieceshash[0].map { it.toInt().toChar()}.joinToString(""))

}