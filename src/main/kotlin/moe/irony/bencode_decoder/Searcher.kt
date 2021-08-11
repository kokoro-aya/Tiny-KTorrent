package moe.irony.bencode_decoder

import moe.irony.utils.fp.Result
import java.math.BigInteger

fun Bencode.search(label: String): Result<Bencode> = when (this) {
    is ByteStringLiteral -> Result.failure("Search: Unlabelled bencode type")
    is DictionaryLiteral -> ByteStringLiteral(label).let {
        return if (this.of.containsKey(it))
            Result(this.of[it])
        else {
            this.of.values
                .map { v -> v.search(label) }
                .firstOrNull(Result<Bencode>::isSuccess)
                ?: Result.failure("Search: The specified label was not found in dictionary")
        }
    }
    is IntLiteral -> Result.failure("Search: Unlabelled bencode type")
    is ListLiteral ->
        this.of
            .map { v -> v.search(label) }
            .firstOrNull(Result<Bencode>::isSuccess)
            ?: Result.failure("Search: The specified label was not found in list")
}

fun Bencode.getIntAttr(attr: String): Result<BigInteger> =
    this.search(attr).flatMap {
        when (it) {
            is IntLiteral -> Result(it.value)
            else -> Result.failure("Cannot retrieve IntAttr $attr from bencode")
        }
    }

fun Bencode.getStringAttr(attr: String): Result<String> =
    this.search(attr).flatMap {
        when (it) {
            is ByteStringLiteral -> Result(it.content)
            else -> Result.failure("Cannot retrieve StringAttr $attr from bencode")
        }
    }

fun Bencode.getDictAttr(attr: String): Result<DictionaryLiteral> =
    this.search(attr).flatMap {
        when (it) {
            is DictionaryLiteral -> Result(it)
            else -> Result.failure("Cannot retrieve DictAttr $attr from bencode")
        }
    }

fun Bencode.getListAttr(attr: String): Result<ListLiteral> =
    this.search(attr).flatMap {
        when (it) {
            is ListLiteral -> Result(it)
            else -> Result.failure("Cannot retrieve ListAttr $attr from bencode")
        }
    }

fun main() {
    val dict =
        DictionaryLiteral(
            ByteStringLiteral("a") to IntLiteral(BigInteger.valueOf(29)),
            ByteStringLiteral("b") to ByteStringLiteral("hello"),
            ByteStringLiteral("c") to DictionaryLiteral(
                ByteStringLiteral("d") to IntLiteral(BigInteger.valueOf(37)),
                ByteStringLiteral("e") to ListLiteral(
                    IntLiteral(BigInteger.valueOf(99)),
                    ByteStringLiteral("world"),
                    IntLiteral(BigInteger.valueOf(299))
                ),
                ByteStringLiteral("f") to ByteStringLiteral("foobar")
            ),
            ByteStringLiteral("g") to ListLiteral(
                DictionaryLiteral(
                    ByteStringLiteral("i") to ByteStringLiteral("baz")
                ),
                DictionaryLiteral(
                    ByteStringLiteral("j") to IntLiteral(BigInteger.valueOf(19384))
                ),
                DictionaryLiteral(
                    ByteStringLiteral("k") to ByteStringLiteral("baaz"),
                    ByteStringLiteral("l") to ByteStringLiteral("baaaaz")
                )
            ),
            ByteStringLiteral("h") to IntLiteral(BigInteger.valueOf(398))
        )

    val a = dict.search("a")
    val b = dict.search("b")
    val d = dict.search("d")
    val e = dict.search("e")
    val f = dict.search("f")
    val j = dict.search("j")
    val k = dict.search("k")
    val l = dict.search("l")

    println("Search completed")
}