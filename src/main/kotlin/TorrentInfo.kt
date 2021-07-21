import utils.fp.Result
import java.math.BigInteger

data class TorrentInfo(
    val pieces: String,
    val pieceLength: Long,
    val length: Long,
    val name: String,
)

data class TorrentSeed(
    val info: TorrentInfo,
    val announce: String,
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

fun main() {
    val message = "d8:announce41:http://bttracker.debian.org:6969/announce7:comment35:" +
            "\"Debian CD from cdimage.debian.org\"13:creation datei1573903810e9:httpse" +
            "edsl145:https://cdimage.debian.org/cdimage/release/10.2.0//srv/cdbuilder." +
            "debian.org/dst/deb-cd/weekly-builds/amd64/iso-cd/debian-10.2.0-amd64-neti" +
            "nst.iso145:https://cdimage.debian.org/cdimage/archive/10.2.0//srv/cdbuild" +
            "er.debian.org/dst/deb-cd/weekly-builds/amd64/iso-cd/debian-10.2.0-amd64-n" +
            "etinst.isoe4:infod6:lengthi351272960e4:name31:debian-10.2.0-amd64-netinst" +
            ".iso12:piece lengthi262144e6:pieces8:xxxxxxxxee"

    val result = Decoder(message).decode()

    val torrent = result.convertToTorrentSeed()

    println("Convert finished")
}