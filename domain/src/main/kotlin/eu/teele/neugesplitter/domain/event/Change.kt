package eu.teele.neugesplitter.domain.event

/**
 * One field of an update payload (format-spec 4.2).
 *
 * An update names only what changed, so each field is either absent or replaced outright. A field
 * that may be cleared is a `Change<T?>` and clearing it is `To(null)`; one that may not is a
 * `Change<T>`, and the type is what stops a required field from being nulled.
 */
sealed interface Change<out T> {

    data object Unchanged : Change<Nothing>

    data class To<out T>(val value: T) : Change<T>
}

/** [Change.To.value] if the field was named, otherwise the value it already had. */
fun <T> Change<T>.or(current: T): T = if (this is Change.To) value else current
