package eu.teele.neugesplitter.sync.probe

import eu.teele.neugesplitter.sync.BucketStore
import eu.teele.neugesplitter.sync.StoreException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class ProbeTest {

    @Test
    fun `a store whose second create loses is accepted (BE-7)`() = runBlocking {
        val store = BucketStore()
        assertEquals(Verdict.Accepted, probe(store))
        assertEquals(setOf("probe"), store.objects.keys)
    }

    @Test
    fun `a probe left by an earlier check is accepted at once (BE-7)`() = runBlocking {
        val store = BucketStore()
        probe(store)
        assertEquals(Verdict.Accepted, probe(store))
    }

    @Test
    fun `a store that overwrites is refused (BE-7)`() = runBlocking {
        assertEquals(Verdict.Refused, probe(BucketStore(conditional = false)))
    }

    @Test
    fun `a store that cannot be written is read-only (BE-8)`() = runBlocking {
        assertEquals(Verdict.ReadOnly, probe(BucketStore(writable = false)))
    }

    @Test
    fun `a store that cannot be reached is unusable (BE-7)`(): Unit = runBlocking {
        val verdict = probe(BucketStore(reachable = false))
        assertIs<StoreException.Unreachable>(assertIs<Verdict.Unusable>(verdict).failure)
    }
}
