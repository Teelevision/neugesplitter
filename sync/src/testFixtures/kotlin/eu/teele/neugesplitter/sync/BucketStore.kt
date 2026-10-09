package eu.teele.neugesplitter.sync

import java.io.IOException

/**
 * A bucket in memory. [conditional] false makes it overwrite like a server that ignores
 * `If-None-Match`; [writable] false makes it refuse writes like a read-only key.
 */
class BucketStore(
    private val conditional: Boolean = true,
    private val writable: Boolean = true,
    @Volatile var reachable: Boolean = true,
) : LogStore {

    val objects = sortedMapOf<String, ByteArray>()

    /** How many times the bucket was listed, each a request somebody pays for. */
    @Volatile var lists = 0

    override suspend fun createIfAbsent(number: ObjectNumber, bytes: ByteArray): Write =
        create("log/${number.name}", bytes)

    override suspend fun get(number: ObjectNumber): ByteArray? {
        reach()
        return objects["log/${number.name}"]
    }

    override suspend fun listFrom(first: ObjectNumber): List<ObjectNumber> {
        reach()
        lists++
        return objects.keys
            .filter { it.startsWith("log/") }
            .mapNotNull { ObjectNumber.parse(it.removePrefix("log/")) }
            .filter { it >= first }
    }

    override suspend fun createProbe(): Write = create("probe", ByteArray(0))

    private fun create(key: String, bytes: ByteArray): Write {
        reach()
        if (!writable) throw StoreException.Denied("read-only")
        if (conditional && key in objects) return Write.TAKEN
        objects[key] = bytes
        return Write.CREATED
    }

    private fun reach() {
        if (!reachable) throw StoreException.Unreachable(IOException("no route to host"))
    }
}
