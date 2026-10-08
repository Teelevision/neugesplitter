package eu.teele.neugesplitter.format

import eu.teele.neugesplitter.format.crypto.Passphrases
import java.util.Locale
import kotlin.math.log2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PassphrasesTest {

    private val german = Passphrases.words(Locale.GERMANY)
    private val english = Passphrases.words(Locale.UK)

    /**
     * Roughly how a German speaker hears [word]: spellings of one sound, and vowel length, merge.
     */
    private fun sound(word: String): String {
        var key = word
        for ((from, to) in SPELLINGS) key = key.replace(from, to)
        key = key.replace(Regex("([aeiou])h"), "$1").replace(Regex("(.)\\1+"), "$1")
        return key.replace(Regex("[dgb]$")) {
            mapOf("d" to "t", "g" to "k", "b" to "p")[it.value]!!
        }
    }

    @Test
    fun `each list has enough distinct lowercase ASCII words (TR-25a)`() {
        for (words in listOf(german, english)) {
            assertTrue(words.size >= 7776, "${words.size} words")
            assertEquals(words.size, words.toSet().size)
            assertTrue(words.all { it.matches(Regex("[a-z]+")) })
            assertTrue(Passphrases.WORDS * log2(words.size.toDouble()) >= 77)
        }
    }

    @Test
    fun `no two words on the German list sound alike (TR-25a)`() {
        val alike = german.groupBy(::sound).values.filter { it.size > 1 }
        assertEquals(emptyList(), alike)
    }

    @Test
    fun `a generated passphrase is six words from the app language's list (PR-12a)`() {
        for ((locale, words) in listOf(Locale.GERMAN to german, Locale.ENGLISH to english)) {
            val passphrase = String(Passphrases.generate(locale))
            val drawn = passphrase.split(" ")
            assertEquals(Passphrases.WORDS, drawn.size)
            assertTrue(words.containsAll(drawn), passphrase)
            assertNotEquals(passphrase, String(Passphrases.generate(locale)))
        }
        assertEquals(english, Passphrases.words(Locale.FRENCH))
    }

    @Test
    fun `a generated passphrase is not weak`() {
        repeat(20) {
            assertFalse(Passphrases.isWeak(Passphrases.generate(Locale.GERMAN)))
            assertFalse(Passphrases.isWeak(Passphrases.generate(Locale.ENGLISH)))
        }
    }

    @Test
    fun `a short or guessable passphrase is weak (PR-12b)`() {
        for (weak in listOf("hallo", "passwort123", "Sommer2026!", "asdfghjkl", "letmein")) {
            assertTrue(Passphrases.isWeak(weak.toCharArray()), weak)
        }
    }

    @Test
    fun `what the group is called counts as guessable`() {
        val chosen = "Ferienhaus Ostsee Kasse".toCharArray()
        assertTrue(Passphrases.isWeak(chosen, listOf("ferienhaus", "ostsee", "kasse")))
    }

    private companion object {
        val SPELLINGS =
            listOf(
                "sch" to "S",
                "ph" to "f",
                "th" to "t",
                "ck" to "k",
                "tz" to "z",
                "dt" to "t",
                "qu" to "kw",
                "x" to "ks",
                "ai" to "ei",
                "ay" to "ei",
                "ey" to "ei",
                "ie" to "i",
                "v" to "f",
                "w" to "v",
                "c" to "k",
                "y" to "i",
            )
    }
}
