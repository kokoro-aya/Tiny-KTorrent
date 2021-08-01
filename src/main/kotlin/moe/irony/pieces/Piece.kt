package moe.irony.pieces

import moe.irony.connect.Block
import moe.irony.connect.BlockStatus
import moe.irony.utils.hash
import moe.irony.utils.hexDecode

/**
 * A fragment of the downloading file, it contains a hash value that must match that from the server,
 * an index indicating the offset of the piece, and a list of blocks that the Piece has
 */
data class Piece(
    private val hashValue: String,
    val index: Int,
    val blocks: List<Block>,
) {

    /**
     * Resets the status of all Blocks in this Piece as Missing
     */
    fun reset() {
        for (b in blocks) {
            b.status = BlockStatus.MISSING
        }
    }

    /**
     * Concatenates the data in each Block and returns it as a whole string representation.
     * Note that this must be calculated after the Piece is completed.
     * @return the data contained in all Blocks concatenated as a string.
     */
    fun getData(): String {
        check(isComplete) { "retrieving data from a non-complete Piece" }
        return blocks.joinToString("") { it.data }
    }

    /**
     * Finds and returns the next Block to be requested (i.e. the first Block that is Missing).
     * The block's status will be changed to pending before returning.
     * If there is not a Missing block, then null will be returned.
     * @return a nullable Block that will be proceeded to the next request.
     */
    fun nextRequest(): Block? {
        for (b in blocks) {
            if (b.status == BlockStatus.MISSING) {
                b.status = BlockStatus.PENDING
                return b
            }
        }
        return null
    }

    /**
     * Updates the block information and sets its status as Retrieved
     * @param offset the offset of the Block within the Piece
     * @param data the data contained within the Block
     */
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

    /**
     * Checks if all Blocks within the Piece has been retrieved. But it doesn't calculate the hash
     * therefore the correctness of the data is unknown.
     */
    val isComplete: Boolean
        get() = blocks.all { it.status == BlockStatus.RETRIEVED };

    /**
     * Checks if the sha1 hash for all retrieved Block data matches the Piece hash from the Torrent meta-info.
     */
    val isHashMatching: Boolean
        get() = getData().hash().hexDecode().map { it.toByte() } == hashValue.map { it.toByte() }
    // hashValue里面的长度变成了40（每个byte后面紧跟一个0），所以跟getData().hash().hexDecode()后的值就不匹配了，尽管按理来说它们实际上是同样的值
    // map { it.toByte() } 可以消除掉这些0，不过会把字符串变成字符数组
}