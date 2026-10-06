package eu.teele.neugesplitter.format

import eu.teele.neugesplitter.format.chain.ChainKey
import eu.teele.neugesplitter.format.chain.identityBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ChainKeyTest {

    private val identity =
        "AGE-SECRET-KEY-1QQQSYQCYQ5RQWZQFPG9SCRGWPUGPZYSNZS23V9CCRYDPK8QARC0SWRYDWG"

    @Test
    fun `an identity decodes to its 32 bytes, in either case`() {
        val bytes = ByteArray(32) { it.toByte() }
        assertContentEquals(bytes, identityBytes(identity))
        assertContentEquals(bytes, identityBytes(identity.lowercase()))
    }

    @Test
    fun `what is not an age identity is refused without being quoted`() {
        val refused =
            listOf(
                identity.replaceFirst("Q", "q"),
                identity.dropLast(1) + "Q",
                identity.replace("AGE-SECRET-KEY-", "AGE-PUBLIC-KEY-"),
                "age13aqvttdk3ujkyjh9kg2w5an6dmy5mq5a84a4uxk3hfhnugfc9p0sy5p2wh",
                identity.replaceFirst("QQQ", "QQB"),
                "",
            )
        for (text in refused) {
            val error = assertFailsWith<IllegalArgumentException> { ChainKey.of(text) }
            if (text.isNotEmpty())
                assertFalse(text.drop(15) in error.message.orEmpty(), error.message)
        }
    }
}
