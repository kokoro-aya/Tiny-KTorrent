package moe.irony.utils

import moe.irony.bencode_decoder.InfoHash
import java.net.URLEncoder
import kotlin.experimental.or
import kotlin.math.floor

val InfoHash.stringRepr: String
    get() = this.map { it.toInt().toChar() }.joinToString("")

fun String.urlEncode(): String {
    return URLEncoder.encode(this, "utf-8")
}

fun String.hexDecode(): String {
    check (length % 2 == 0) { "Must have an even length" }
    return this.chunked(2)
        .map { it.toInt(16).toChar() }
        .joinToString("")
}

fun String.hexEncode(): String {
    val hexDigits = "0123456789ABCDEF"

    return buildString {
        for (c in this@hexEncode) {
            val fst = hexDigits[c.code.shr(4)]
            val snd = hexDigits[c.code.and(15)]
            append(fst)
            append(snd)
        }
    }
}

fun ByteArray.hasPiece(index: Int): Boolean {
    val byteIndex = index / 8
    val offset = index % 8
    return this[byteIndex].toInt().shr(7 - offset).and(1) != 0
}

fun ByteArray.setPiece(index: Int) {
    val byteIndex = index / 8
    val offset = index % 8
    this[byteIndex] = this[byteIndex].or(1.shl(7 - offset).toByte())
}

fun Long.formatTime(): String {
    if (this < 0) return "inf"
    val hh = "%02d".format(this / 3600)
    val mm = "%02d".format((this % 3600) / 60)
    val ss = "%02d".format(this % 60)

    return if (hh != "00") {
        "$hh:$mm:$ss"
    } else {
        "$mm:$ss"
    }
}