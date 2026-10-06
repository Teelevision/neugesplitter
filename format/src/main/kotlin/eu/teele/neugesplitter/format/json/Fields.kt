package eu.teele.neugesplitter.format.json

import eu.teele.neugesplitter.domain.Malformed
import eu.teele.neugesplitter.domain.event.Change
import java.time.DateTimeException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Reading the fields of format-spec 4 out of a tree that has already passed [restricted].
 *
 * A field that is absent is either missing or unchanged, and `null` is legal only where an update
 * clears an optional field (format-spec 2.2); everything else about a field is its decoder's. A
 * field nobody asks for is never looked at, which is what ignoring it means (format-spec 8).
 * Messages name fields, never their values (TR-38).
 */

/** Decodes the value of the field it is given the name of. */
internal typealias Decoder<T> = (JsonElement, String) -> T

internal fun <T> JsonObject.required(name: String, decode: Decoder<T>): T =
    when (val value = this[name]) {
        null -> throw Malformed("'$name' is missing")
        JsonNull -> nullHere(name)
        else -> decode(value, name)
    }

internal fun <T> JsonObject.optional(name: String, decode: Decoder<T>): T? =
    when (val value = this[name]) {
        null -> null
        JsonNull -> nullHere(name)
        else -> decode(value, name)
    }

/** A field of an update that may not be cleared. */
internal fun <T> JsonObject.change(name: String, decode: Decoder<T>): Change<T> =
    when (val value = this[name]) {
        null -> Change.Unchanged
        JsonNull -> throw Malformed("'$name' is not optional and cannot be cleared")
        else -> Change.To(decode(value, name))
    }

/** An optional field of an update, which `null` clears. */
internal fun <T> JsonObject.clearable(name: String, decode: Decoder<T>): Change<T?> =
    when (val value = this[name]) {
        null -> Change.Unchanged
        JsonNull -> Change.To(null)
        else -> Change.To(decode(value, name))
    }

internal fun JsonObject.forbid(name: String, where: String) {
    if (name in this) throw Malformed("'$name' may not appear in $where")
}

private fun nullHere(name: String): Nothing =
    throw Malformed("'$name' is null, which only an update may write, to clear an optional field")

internal fun text(value: JsonElement, name: String): String {
    val primitive = value as? JsonPrimitive
    if (primitive == null || !primitive.isString) throw Malformed("'$name' is not a string")
    return primitive.content
}

/** An integer; [restricted] has already kept it within ±2⁵³. */
internal fun integer(value: JsonElement, name: String): Long {
    val primitive = value as? JsonPrimitive
    if (primitive == null || primitive.isString) throw Malformed("'$name' is not an integer")
    // `true`, `false` and `null` are the other unquoted primitives, and none of them parses.
    return primitive.content.toLongOrNull() ?: throw Malformed("'$name' is not an integer")
}

internal fun obj(value: JsonElement, name: String): JsonObject =
    value as? JsonObject ?: throw Malformed("'$name' is not an object")

internal fun array(value: JsonElement, name: String): JsonArray =
    value as? JsonArray ?: throw Malformed("'$name' is not an array")

/** A string field read by one of the domain's parsers, whose message may quote the value. */
internal fun <T> parsed(parse: (String) -> T): Decoder<T> = { value, name ->
    val content = text(value, name)
    try {
        parse(content)
    } catch (e: Malformed) {
        throw e
    } catch (_: IllegalArgumentException) {
        throw Malformed("'$name' is not valid")
    } catch (_: DateTimeException) {
        throw Malformed("'$name' is not a valid date")
    }
}

/** An array of distinct values. */
internal fun <T> distinct(element: Decoder<T>): Decoder<List<T>> = { value, name ->
    val items = array(value, name).map { element(it, name) }
    if (items.toSet().size != items.size) throw Malformed("'$name' holds a value twice")
    items
}
