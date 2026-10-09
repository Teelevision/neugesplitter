package eu.teele.neugesplitter.sync.probe

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.format.Unsupported
import eu.teele.neugesplitter.sync.Compromised
import eu.teele.neugesplitter.sync.LogStore
import eu.teele.neugesplitter.sync.ObjectNumber
import eu.teele.neugesplitter.sync.Retained
import eu.teele.neugesplitter.sync.Sealing

/** What a location offers an installation that holds nothing of it yet (BE-9). */
sealed interface Offer {

    /** No log at all: a group can start one here. */
    data object Empty : Offer

    /** Something that is no group's log, which must not be joined or written into. */
    data object Foreign : Offer

    /** An unencrypted group's log. */
    class Open(val group: GroupId) : Offer

    /** An encrypted group's root object; which group it is shows once a passphrase opens it. */
    class Locked(val root: ByteArray) : Offer
}

/**
 * What [store] offers, told by its object 0 (format-spec 2.1).
 *
 * @throws eu.teele.neugesplitter.sync.StoreException if the backend could not be asked.
 */
suspend fun offer(store: LogStore): Offer {
    val root =
        store.get(ObjectNumber.ROOT)
            ?: return if (store.listFrom(ObjectNumber.ROOT).isEmpty()) Offer.Empty
            else Offer.Foreign
    return when (root.firstOrNull()) {
        '{'.code.toByte() -> founded(root, null, Sealing.Plain)?.let(Offer::Open) ?: Offer.Foreign
        '-'.code.toByte() -> Offer.Locked(root)
        else -> Offer.Foreign
    }
}

/**
 * The group whose log follows [root] in [store], read with [sealing]; null if no group's does.
 *
 * @throws eu.teele.neugesplitter.sync.StoreException if the backend could not be asked.
 */
suspend fun locked(store: LogStore, root: ByteArray, sealing: Sealing): GroupId? {
    val first = store.get(ObjectNumber(ObjectNumber.ROOT.value + 1)) ?: return null
    return founded(first, Retained(ObjectNumber.ROOT, root), sealing)
}

/** The group [bytes] found, if they are a log's first object. */
private fun founded(bytes: ByteArray, previous: Retained?, sealing: Sealing): GroupId? =
    try {
        (sealing.read(bytes, previous).carried.events.firstOrNull() as? Event.GroupCreated)
            ?.group
            ?.id
    } catch (_: Malformed) {
        null
    } catch (_: Unsupported) {
        null
    } catch (_: Compromised) {
        null
    }
