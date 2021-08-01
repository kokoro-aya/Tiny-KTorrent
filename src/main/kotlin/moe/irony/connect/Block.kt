package moe.irony.connect

enum class BlockStatus {
    MISSING, PENDING, RETRIEVED
}

/**
 * A part of a piece that's requested and transferred from the peer to another.
 * By convention, a Block has a size of 2.pow(14) bytes except for the last one.
 */
data class Block(
    val piece: Int,
    val offset: Int,
    val length: Int,
    var status: BlockStatus,
    var data: String
)