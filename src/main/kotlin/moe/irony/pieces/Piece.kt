package moe.irony.pieces

import moe.irony.connect.Block
import moe.irony.connect.BlockStatus
import moe.irony.utils.hash
import moe.irony.utils.hexDecode

data class Piece(
    private val hashValue: String,
    val index: Int,
    val blocks: List<Block>,
) {
    fun reset() {
        for (b in blocks) {
            b.status = BlockStatus.MISSING
        }
    }

    fun getData(): String {
        check(isComplete) { "retrieving data from a non-complete Piece" }
        return blocks.joinToString("") { it.data }
    }

    fun nextRequest(): Block? {
        for (b in blocks) {
            if (b.status == BlockStatus.MISSING) {
                b.status = BlockStatus.PENDING
                return b
            }
        }
        return null
    }

    fun blockReceived(offset: Int, data: String) {
        for (b in blocks) {
            if (b.offset == offset) {
                b.status = BlockStatus.RETRIEVED
                b.data = data
            }
        }
        throw IllegalStateException(
            "Trying to complete a non-existing block $offset in piece $index")
    }

    val isComplete: Boolean
        get() = blocks.all { it.status == BlockStatus.RETRIEVED };

    val isHashMatching: Boolean
        get() = getData().hash().hexDecode() == hashValue
}