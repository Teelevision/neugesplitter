package eu.teele.neugesplitter.format

/**
 * An object written by a later version: an unknown `type`, `scheme` or `interval`, or a later
 * format version (format-spec 8).
 *
 * Reading stops here exactly as it does at a [eu.teele.neugesplitter.domain.Malformed] object, but
 * the group is explained differently: nothing is wrong with it, this installation is out of date.
 */
class Unsupported(message: String) : Exception(message)
