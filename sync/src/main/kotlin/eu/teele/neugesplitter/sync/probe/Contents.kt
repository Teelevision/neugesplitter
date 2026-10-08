package eu.teele.neugesplitter.sync.probe

import eu.teele.neugesplitter.sync.LogStore
import eu.teele.neugesplitter.sync.ObjectNumber

/** What a location holds, measured against one group as this installation has it (BE-9). */
enum class Contents {
    /** No log at all: the group can start one here. */
    EMPTY,

    /** This group, at the object this installation holds last. */
    SAME,

    /** This group, but further behind than this installation. */
    OLDER,

    /** This group, further on than this installation (BE-10). */
    NEWER,

    /** A log that is not this group's, which must not be written into (BE-9). */
    FOREIGN,
}

/**
 * Compares the log at [store] with a group whose object 0 is [first] and whose last object is
 * [head], both null for a group never shared. Object 0 decides whose log it is: the same bytes are
 * the same group, encrypted or not.
 *
 * @throws eu.teele.neugesplitter.sync.StoreException if the backend could not be asked.
 */
suspend fun contents(store: LogStore, first: ByteArray?, head: ObjectNumber?): Contents {
    val root = store.get(ObjectNumber.ROOT)
    if (root == null) {
        return if (store.listFrom(ObjectNumber.ROOT).isEmpty()) Contents.EMPTY else Contents.FOREIGN
    }
    if (first == null || head == null || !root.contentEquals(first)) return Contents.FOREIGN
    return when (store.listFrom(head).maxOrNull()) {
        null -> Contents.OLDER
        head -> Contents.SAME
        else -> Contents.NEWER
    }
}
