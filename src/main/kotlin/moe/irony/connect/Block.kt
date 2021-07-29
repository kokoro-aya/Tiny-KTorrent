package moe.irony.connect

enum class BlockStatus {
    MISSING, PENDING, RETRIEVED
}

data class Block(
    val piece: Int,
    val offset: Int,
    val length: Int,
    var status: BlockStatus,
    var data: String
)