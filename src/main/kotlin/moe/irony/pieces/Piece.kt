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
                return // 这里忘记加return了
            }
        }
        throw IllegalStateException(
            "Trying to complete a non-existing block $offset in piece $index")
    }

    val isComplete: Boolean
        get() = blocks.all { it.status == BlockStatus.RETRIEVED };

    val isHashMatching: Boolean
        get() = getData().hash().hexDecode().map { it.toByte() } == hashValue.map { it.toByte() }
    // hashValue里面的长度变成了40（每个byte后面紧跟一个0），所以跟getData().hash().hexDecode()后的值就不匹配了，尽管按理来说它们实际上是同样的值
    // map { it.toByte() } 可以消除掉这些0，不过会把字符串变成字符数组
}