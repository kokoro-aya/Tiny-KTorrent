package moe.irony.utils

import moe.irony.utils.fp.unfold
import java.net.URLEncoder
import kotlin.experimental.or

fun String.hexToUrlEncode(): String {
    check (length % 2 == 0) { "Must have an even length" }
    return this.chunked(2)
        .joinToString("") { it.toInt(16).urlEncode() }
}

private fun Int.urlEncode(): String {
    return when {
        this.toChar() in '0' .. '9'
                || this.toChar() in 'a' .. 'z'
                || this.toChar() in 'A' .. 'Z'
                || this.toChar() in ".-_~" -> "${this.toChar()}"
        else -> "%${"%02X".format(this)}"
    }
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

// 这里有个坑，必须用UByte
fun List<UByte>.bytesToInt(): Int = this.map { it.toInt() }.fold(0) { left, right ->
    left * 256 + right
}

fun Int.intToBytes(): List<UByte> = unfold(this) {
    if (it <= 1)
        null
    else
        (it % 256).toUByte() to it / 256
}.reversed()

fun main() {
//    val a = listOf<UByte>(0x1a.toUByte(), 0xe1.toUByte())
//    println(a.bytesToInt())

//    val a = "90493C18F577D24D5646C5075193BF57FAABDCF6"
//    println(a.hexToUrlEncode())

    val a = arrayOf(104, 105, 106, 107).map { it.toUByte() }
    val b = a.bytesToInt()
    println(b)
    val c = b.intToBytes()
    println(c)
}