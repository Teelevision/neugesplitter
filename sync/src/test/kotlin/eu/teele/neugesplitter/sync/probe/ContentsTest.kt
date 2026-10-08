package eu.teele.neugesplitter.sync.probe

import eu.teele.neugesplitter.sync.BucketStore
import eu.teele.neugesplitter.sync.ObjectNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class ContentsTest {

    private val first = byteArrayOf(1, 2, 3)

    private fun store(vararg numbers: Int, root: ByteArray = first) =
        BucketStore().apply {
            numbers.forEach {
                objects["log/${ObjectNumber(it).name}"] = if (it == 0) root else byteArrayOf(9)
            }
        }

    @Test
    fun `a location without a log is empty (BE-9)`() = runBlocking {
        assertEquals(Contents.EMPTY, contents(BucketStore(), null, null))
        assertEquals(Contents.EMPTY, contents(BucketStore(), first, ObjectNumber(2)))
    }

    @Test
    fun `the same object 0 at the same head is this group (BE-9)`() = runBlocking {
        assertEquals(Contents.SAME, contents(store(0, 1, 2), first, ObjectNumber(2)))
    }

    @Test
    fun `the same object 0 further on or behind is another state (BE-10)`() = runBlocking {
        assertEquals(Contents.NEWER, contents(store(0, 1, 2, 3), first, ObjectNumber(2)))
        assertEquals(Contents.OLDER, contents(store(0, 1), first, ObjectNumber(2)))
    }

    @Test
    fun `another object 0 is foreign (BE-9)`() = runBlocking {
        assertEquals(
            Contents.FOREIGN,
            contents(store(0, 1, root = byteArrayOf(7)), first, ObjectNumber(1)),
        )
    }

    @Test
    fun `any log is foreign to a group never shared (BE-9)`() = runBlocking {
        assertEquals(Contents.FOREIGN, contents(store(0), null, null))
    }

    @Test
    fun `objects without an object 0 are foreign (BE-9)`() = runBlocking {
        assertEquals(Contents.FOREIGN, contents(store(1, 2), null, null))
    }
}
