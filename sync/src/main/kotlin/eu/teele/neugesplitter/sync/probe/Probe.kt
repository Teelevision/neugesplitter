package eu.teele.neugesplitter.sync.probe

import eu.teele.neugesplitter.sync.LogStore
import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.StoreException
import eu.teele.neugesplitter.sync.Write

/** What the backend check found (BE-7). */
sealed interface Verdict {

    /** Reachable, the credentials work, and a second create of one key loses. */
    data object Accepted : Verdict

    /** Readable but not writable: fine for following a group (BE-8). */
    data object ReadOnly : Verdict

    /** It overwrote instead of refusing, so it would lose entries (sync-design 4). */
    data object Refused : Verdict

    /** It could not be read at all. */
    data class Unusable(val failure: StoreException) : Verdict
}

/**
 * Reads the root object, then creates `probe` twice and requires the second to lose. A probe left
 * by an earlier check loses the first time, which says the same.
 */
suspend fun probe(store: LogStore): Verdict {
    try {
        store.get(ObjectNumber.ROOT)
    } catch (e: StoreException) {
        return Verdict.Unusable(e)
    }
    return try {
        if (store.createProbe() == Write.TAKEN || store.createProbe() == Write.TAKEN) {
            Verdict.Accepted
        } else {
            Verdict.Refused
        }
    } catch (_: StoreException.Denied) {
        Verdict.ReadOnly
    } catch (e: StoreException) {
        Verdict.Unusable(e)
    }
}
