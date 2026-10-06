package eu.teele.neugesplitter.format.json

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.event.Event
import eu.teele.neugesplitter.format.Unsupported
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * One event as JSON text in the wire schema, which is what the local event log keeps (TR-17b).
 *
 * The text is the durable form and the caller stores it as it is (TR-17c). Reading goes through the
 * same restrictions and limits as a log object, because a row may hold an event that arrived in
 * one.
 */
object EventJson {

    /**
     * Compact JSON, as [LogObject.write] writes each of its events.
     *
     * @throws Malformed if the event breaks a limit of format-spec 2.2 on its own, because no log
     *   object could ever carry it and every reader would refuse the one that tried.
     */
    fun write(event: Event): String {
        val text = Json.encodeToString(JsonElement.serializer(), Events.encode(event))
        restricted(text.encodeToByteArray())
        return text
    }

    /**
     * @throws Malformed if [text] breaks any rule of format-spec 2.2 or 4.
     * @throws Unsupported if it is an event this version does not know.
     */
    fun read(text: String): Event =
        Events.decode(Json.parseToJsonElement(restricted(text.encodeToByteArray())))
}
