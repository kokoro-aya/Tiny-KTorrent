package moe.irony.connect

/**
 * Represents different types of BitTorrent messages.
 */
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

/**
 * An object that encapsulates a piece of BitTorrent message
 * @param id MessageId, indicates the type of this message
 * @param payload payload of the message encoded in a string
 */
data class BitTorrentMessage(
    val id: MessageId, val payload: String, val messageLength: Int = payload.length + 1
) {

    /**
     * This function is rewritten to provide a string representation of the message that conforms to its interpretation.
     * More details could be found here: https://blog.jse.li/posts/torrent/#interpreting-messages
     */
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