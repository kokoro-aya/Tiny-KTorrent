package moe.irony

import moe.irony.bencode_decoder.Decoder
import java.io.File

fun main(args: Array<String>) { // Charset.ASCII是另一个天坑。。。
    val m = File("MoralPsychHandbook.pdf.torrent").readText(Charsets.US_ASCII)
    val d = File("debian-10.10.0-amd64-netinst.iso.torrent").readText(Charsets.US_ASCII)
    val benc = Decoder(d).decode()
    val unsg = benc.unsafeGet()

    println("Finished!")
}