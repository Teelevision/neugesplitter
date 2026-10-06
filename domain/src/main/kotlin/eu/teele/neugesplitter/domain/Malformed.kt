package eu.teele.neugesplitter.domain

/**
 * Something in the log cannot be read: an event that contradicts the state before it, or a side
 * whose input cannot be reconciled with its amount.
 *
 * Reading stops here rather than skipping the object (format-spec 8). A skipped event is a balance
 * that is wrong on one device and right on every other, silently and permanently.
 */
class Malformed(message: String) : IllegalArgumentException(message)
