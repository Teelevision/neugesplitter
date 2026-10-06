package eu.teele.neugesplitter.format.json

import eu.teele.neugesplitter.domain.Malformed
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * The limits of format-spec 2.2, each inclusive. Raising any of them is a format version change.
 */
object Limits {
    const val SIZE: Int = 8 * 1024 * 1024
    const val DEPTH: Int = 32
    const val STRING: Int = 64 * 1024
    const val EVENTS: Int = 10_000
    const val NUMBER: Long = 1L shl 53
}

/**
 * [bytes] as text, once they have passed the JSON restrictions and limits of format-spec 2.2.
 *
 * kotlinx.serialization keeps the last of two equal keys and has no limits of its own, so nothing
 * reaches it that has not been through here first. The checks run as the text is consumed and
 * nothing is sized from what the text declares (TR-39). Whether a `null` or a boolean is allowed
 * depends on where it is, which is the codec's business, not this one's.
 *
 * @throws Malformed at the first rule broken. The message never quotes the content (TR-38).
 */
internal fun restricted(bytes: ByteArray): String {
    if (bytes.size > Limits.SIZE) {
        throw Malformed("an object is ${bytes.size} bytes, more than ${Limits.SIZE}")
    }
    if (bytes.size >= 3 && bytes[0] == BOM[0] && bytes[1] == BOM[1] && bytes[2] == BOM[2]) {
        throw Malformed("an object begins with a byte-order mark")
    }
    val text =
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw Malformed("an object is not UTF-8")
        }
    Scanner(text).document()
    return text
}

private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

/**
 * Recursive descent over RFC 8259, recognising and discarding. Recursion is bounded by
 * [Limits.DEPTH], which is checked before each level is entered.
 */
private class Scanner(private val text: String) {

    private var at = 0

    fun document() {
        space()
        if (peek() != '{') throw Malformed("an object is not a JSON object")
        value(depth = 1, counted = false)
        space()
        if (at < text.length) throw Malformed("an object continues after it ends, at $at")
    }

    /** [counted] marks the top-level `events` array, whose elements are limited in number. */
    private fun value(depth: Int, counted: Boolean) {
        when (peek()) {
            '{' -> obj(depth)
            '[' -> array(depth, counted)
            '"' -> string(keep = false)
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> number()
        }
    }

    private fun obj(depth: Int) {
        enter(depth)
        val keys = HashSet<String>()
        space()
        if (take('}')) return
        do {
            space()
            if (peek() != '"') unexpected()
            val key = string(keep = true)!!
            if (!keys.add(key)) throw Malformed("a key appears twice in one object, at $at")
            space()
            expect(':')
            space()
            value(depth + 1, counted = depth == 1 && key == "events")
            space()
        } while (take(','))
        expect('}')
    }

    private fun array(depth: Int, counted: Boolean) {
        enter(depth)
        space()
        if (take(']')) return
        var count = 0
        do {
            space()
            if (counted && ++count > Limits.EVENTS) {
                throw Malformed("an object carries more than ${Limits.EVENTS} events")
            }
            value(depth + 1, counted = false)
            space()
        } while (take(','))
        expect(']')
    }

    private fun enter(depth: Int) {
        if (depth > Limits.DEPTH) throw Malformed("an object nests deeper than ${Limits.DEPTH}")
        at++
    }

    /** The value of the string, if [keep]; its length in UTF-8 is limited either way. */
    private fun string(keep: Boolean): String? {
        at++
        val value = if (keep) StringBuilder() else null
        var length = 0
        while (true) {
            if (at >= text.length) throw Malformed("a string is not closed")
            val c = text[at++]
            when {
                c == '"' -> break
                c == '\\' -> {
                    val unit = escape()
                    if (Character.isHighSurrogate(unit)) {
                        // The decoder pairs raw surrogates; only an escape can leave one alone.
                        if (!text.startsWith("\\u", at)) unpaired()
                        at++
                        val low = escape()
                        if (!Character.isLowSurrogate(low)) unpaired()
                        value?.append(unit)?.append(low)
                        length += 4
                    } else {
                        if (Character.isLowSurrogate(unit)) unpaired()
                        value?.append(unit)
                        length += utf8(unit)
                    }
                }
                c < ' ' -> throw Malformed("a string holds a control character, at $at")
                else -> {
                    value?.append(c)
                    // A raw pair is two units at two bytes each.
                    length += if (Character.isSurrogate(c)) 2 else utf8(c)
                }
            }
            if (length > Limits.STRING) {
                throw Malformed("a string is longer than ${Limits.STRING} bytes")
            }
        }
        return value?.toString()
    }

    /** The unit an escape stands for, with [at] just past its backslash. */
    private fun escape(): Char {
        if (at >= text.length) unexpected()
        return when (text[at++]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                if (at + 4 > text.length) unexpected()
                var unit = 0
                repeat(4) {
                    val digit = Character.digit(text[at++], 16)
                    if (digit < 0) unexpected()
                    unit = unit * 16 + digit
                }
                unit.toChar()
            }
            else -> unexpected()
        }
    }

    private fun utf8(unit: Char): Int =
        when {
            unit.code < 0x80 -> 1
            unit.code < 0x800 -> 2
            else -> 3
        }

    private fun number() {
        take('-')
        if (!take('0')) {
            if (peek() !in '1'..'9') unexpected()
            var digits = 0
            var magnitude = 0L
            while (peek() in '0'..'9') {
                // 2⁵³ has sixteen digits, so a seventeenth is out of range before it overflows.
                if (++digits > 16) beyond()
                magnitude = magnitude * 10 + (text[at++] - '0')
            }
            if (magnitude > Limits.NUMBER) beyond()
        }
        if (peek() == '.' || peek() == 'e' || peek() == 'E') {
            throw Malformed("a number is not an integer, at $at")
        }
    }

    private fun literal(word: String) {
        if (!text.startsWith(word, at)) unexpected()
        at += word.length
    }

    private fun space() {
        while (
            at < text.length && text[at].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
        ) at++
    }

    private fun peek(): Char = if (at < text.length) text[at] else END

    private fun take(c: Char): Boolean = (peek() == c).also { if (it) at++ }

    private fun expect(c: Char) {
        if (!take(c)) unexpected()
    }

    private fun unexpected(): Nothing = throw Malformed("an object is not JSON, at $at")

    private fun unpaired(): Nothing =
        throw Malformed("a string holds an unpaired surrogate, at $at")

    private fun beyond(): Nothing = throw Malformed("a number is beyond ±2⁵³, at $at")

    private companion object {
        /** No token begins with U+0000, so a NUL in the text is refused as the end of it is. */
        const val END = '\u0000'
    }
}
