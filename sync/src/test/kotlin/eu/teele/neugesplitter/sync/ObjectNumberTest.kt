package eu.teele.neugesplitter.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ObjectNumberTest {

    @Test
    fun `a number is named by seven lowercase hex digits (format-spec 2)`() {
        assertEquals("0000000", ObjectNumber.ROOT.name)
        assertEquals("0000134", ObjectNumber(0x134).name)
        assertEquals("fffffff", ObjectNumber(ObjectNumber.MAX).name)
        assertEquals(ObjectNumber(0xabc), ObjectNumber.parse("0000abc"))
    }

    @Test
    fun `names of any other shape are not numbers (format-spec 2)`() {
        for (name in listOf("", "000000", "00000000", "000000A", "000000g", "probe", "-000001")) {
            assertNull(ObjectNumber.parse(name), name)
        }
        assertFailsWith<IllegalArgumentException> { ObjectNumber(-1) }
        assertFailsWith<IllegalArgumentException> { ObjectNumber(ObjectNumber.MAX + 1) }
    }
}
