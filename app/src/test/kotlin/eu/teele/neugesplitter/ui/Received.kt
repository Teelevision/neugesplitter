package eu.teele.neugesplitter.ui

import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.format.json.BatchId
import eu.teele.neugesplitter.format.json.LogObject
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.sync.Fetched
import eu.teele.neugesplitter.sync.ObjectNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Receives [events] into [group] as object [n], as from another installation; what was waiting here
 * is written first as object 0 if nothing has been.
 */
fun GroupRepository.receive(group: GroupId, today: EntryDate, n: Int, vararg events: Event) =
    runBlocking {
        val log = local(group, { today }, Dispatchers.Unconfined)
        if (log.head() == null) {
            val batch = checkNotNull(log.batch(BatchId.parse("%032x".format(0xba00))))
            log.written(ObjectNumber.ROOT, LogObject(batch.id, null, batch.events).write())
        }
        val bytes =
            LogObject(BatchId.parse("%032x".format(0xba00 + n)), null, events.toList()).write()
        log.received(listOf(Fetched(ObjectNumber(n), bytes, LogObject.receive(bytes))))
    }
