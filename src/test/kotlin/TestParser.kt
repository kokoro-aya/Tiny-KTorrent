import moe.irony.bencode_decoder.*
import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.test.assertEquals

class TestParser {
    @Test
    fun testDecodeString() {
        val input = "4:spam"
        val decoded = Decoder(input).decode()
        decoded.forEach({ assertEquals(it, ByteStringLiteral("spam")) }, {}, {})
    }

    @Test
    fun testDecodeInteger() {
        val input = "i3e"
        val decoded = Decoder(input).decode()
        decoded.forEach({ assertEquals(it, IntLiteral(BigInteger("3"))) }, {}, {})
    }

    @Test
    fun testDecodeList() {
        val input = "l4:spam4:eggse" // problem: "l4:spam:4:eggse" will cause inf loop
        val decoded = Decoder(input).decode()
        val expected = ListLiteral(mutableListOf(
            ByteStringLiteral("spam"), ByteStringLiteral("eggs")
        ))
        decoded.forEach({ assertEquals(it, expected) }, {}, {})
    }

    @Test
    fun testDecodeDictionary() {
        val input = "d3:cow3:moo4:spam4:eggse"
        val decoded = Decoder(input).decode()
        val expected = DictionaryLiteral(mutableMapOf(
            ByteStringLiteral("cow") to ByteStringLiteral("moo"),
            ByteStringLiteral("spam") to ByteStringLiteral("eggs")
        ))
        decoded.forEach({ assertEquals(it, expected) }, {}, {})
    }

    @Test
    fun testEmptyString() {
        val input = "d4:size0:e"
        val decoded = Decoder(input).decode()
        val expected = DictionaryLiteral(
            mutableMapOf(ByteStringLiteral("size") to ByteStringLiteral(""))
        )
        decoded.forEach({ assertEquals(it, expected) }, {}, {})
    }

}