package moe.irony.bencode_decoder

import khttp.post
import moe.irony.utils.fp.Result
import java.io.File
import java.math.BigInteger

// 借鉴了 kotlin-bencode （https://github.com/ciferkey/kotlin-bencode）
sealed class Bencode {
    abstract fun encode(): String
}

data class IntLiteral(val value: BigInteger): Bencode() {
    override fun encode(): String = INTEGER_MARKER + value.toString(10) + ENDING_MARKER
}
data class ByteStringLiteral(val content: String): Bencode() {
    override fun encode(): String = content.length.toString() + SEPARATOR + content
}
data class ListLiteral(val of: MutableList<Bencode>): Bencode() {
    override fun encode(): String {
        return of.map { it.encode() }
            .joinToString("", prefix = "$LIST_MARKER", postfix = "$ENDING_MARKER")
    }
}
data class DictionaryLiteral(val of: MutableMap<Bencode, Bencode>): Bencode() {
    override fun encode(): String {
        return of.entries.map { it.key.encode() + it.value.encode() }
            .joinToString("", prefix = "$DICT_MARKER", postfix = "$ENDING_MARKER")
    }
}

private const val INTEGER_MARKER = 'i'
private const val LIST_MARKER = 'l'
private const val DICT_MARKER = 'd'
private const val ENDING_MARKER = 'e'
private const val SEPARATOR = ':'

class Decoder(val input: String) {

    val iter = input.iterator()

    fun decode(): Result<Bencode> {
        if (iter.hasNext()) {
            val marker = iter.nextChar()
            return when (marker) {
                in '0'..'9' -> decodeString(marker)
                INTEGER_MARKER -> decodeInteger()
                LIST_MARKER -> decodeList()
                DICT_MARKER -> decodeDict()
                else -> Result.failure("Unknown identifier $marker")
            }
        }
        return Result.failure("Nothing to decode")
    }

    private fun decodeString(firstDigit: Char): Result<Bencode> {
        return iter.readWhile { it.isDigit() }
            .flatMap { length ->
                iter.consume(SEPARATOR).flatMap {
                    iter.readFor("$firstDigit$length".toInt()).map {
                        ByteStringLiteral(it)
                    }
                }
            }
    }

    private fun decodeInteger(): Result<Bencode> {
        return iter.readUntil(ENDING_MARKER).flatMap {
            Result.of {
                IntLiteral(it.toBigInteger())
            }
        }
    }

    private fun decodeList(): Result<Bencode> {
        return iter.instance().flatMap {
            Result.of {
                val items = mutableListOf<Bencode>()
                while (!iter.consume(ENDING_MARKER).getOrElse(false)) {
                    decode().map {
                        items.add(it)
                    }
                }
                ListLiteral(items)
            }.mapFailure("Failed decoding list. No TERMINATOR $ENDING_MARKER")
        }
    }

    private fun decodeDict(): Result<Bencode> {
        return iter.instance().flatMap {
            Result.of {
                val items = mutableMapOf<Bencode, Bencode>()
                while (!iter.consume(ENDING_MARKER).getOrElse(false)) {
                    decode().fanout {
                        decode()
                    }.forEach({
                        items[it.first] = it.second
                    }, {}, {})
                }
                DictionaryLiteral(items)
            }.mapFailure("Failed decoding list. No TERMINATOR $ENDING_MARKER")
        }
    }
}

interface CharIterator {
    fun hasNext(): Boolean
    fun nextChar(): Char
    fun peek(): Char
}

fun String.iterator(): CharIterator = object: CharIterator {
    private var index = 0

    override fun hasNext(): Boolean = index < length

    override fun nextChar(): Char {
        return get(index++)
    }

    override fun peek(): Char {
        return get(index)
    }
}

fun CharIterator.readWhile(pred: (Char) -> Boolean): Result<String> {
    return Result.of {
        buildString {
            while (pred(this@readWhile.peek())) {
                append(this@readWhile.nextChar())
            }
        }
    }.mapFailure("Failed reading based on given predicate")
}
fun CharIterator.readUntil(terminator: Char): Result<String> {
    return this.readWhile { it != terminator }.flatMap { string ->
        this.consume(terminator).map { _ ->
            string
        }
    }.mapFailure("Failed reading until '$terminator'")
}
fun CharIterator.consume(char: Char): Result<Boolean> {
    return if (this.peek() == char) {
        this.nextChar()
        Result(true)
    } else {
        Result.failure("Could not consume '$char'")
    }
}
fun CharIterator.instance(): Result<CharIterator> {
    return Result(this)
}
fun CharIterator.readFor(size: Int): Result<String> {
    return Result.of {
        val sub = mutableListOf<Char>()
        for (i in 0 until size) {
            sub.add(this.nextChar())
        }
        sub.joinToString("")
    }.mapFailure("Was not able to read $size characters")
}

inline fun <V, U> Result<V>.fanout(crossinline other: () -> Result<U>): Result<Pair<V, U>> {
    return flatMap { outer ->
        other().map { outer to it }
    }
}

fun main() {
    val f = File("psy200.txt")
    val g = f.readBytes()
    val v = g.map { it.toChar() }.joinToString("")
    val b = Decoder(v).decode()
    val u = b.unsafeGet()
    val z = (u as ByteStringLiteral).content
    z.map { it.code.toByte() }.forEachIndexed { i, it ->
        if (i % 25 == 0) println()
        print("${"%02x".format(it)} ")
    }
    println()
    println(z.length)
}