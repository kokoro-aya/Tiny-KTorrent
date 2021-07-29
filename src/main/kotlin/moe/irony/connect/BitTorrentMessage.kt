package moe.irony.connect

enum class MessageId(val id: Int) {
    KEEP_ALIVE(-1),
    CHOKE(0),
    UNCHOKE(1),
    INTERESTED(2),
    NOT_INTERESTED(3),
    HAVE(4),
    BITFIELD(5),
    REQUEST(6),
    PIECE(7),
    CANCEL(8),
    PORT(9)
}

data class BitTorrentMessage(
    val id: MessageId, val payload: String, val messageLength: Int = payload.length + 1
) {
    override fun toString(): String {
        return buildString {
            append(messageHead.map { it.toChar() }.joinToString(""))
            append(payload)
        }
    }

    private val messageHead: List<Byte>
        get() = arrayOf(
            messageLength / 1000 % 10,
            messageLength / 100 % 10,
            messageLength / 10 % 10,
            messageLength % 10,
            id.id
        ).map { it.toByte() }

}

fun main() {
    val s = BitTorrentMessage(MessageId.PORT, "abcdefghijklmnopqrstuvwxyz")
    println(s.toString())
}