package eu.teele.neugesplitter.format.crypto

import com.nulabinc.zxcvbn.Zxcvbn
import java.nio.CharBuffer
import java.security.SecureRandom
import java.util.Locale

/** The passphrases a group is locked with: made up by the app, or judged on the device. */
object Passphrases {

    /** About 77 bits from the shipped list (TR-25a). */
    const val WORDS = 6

    /**
     * Below this, an attacker with the root object and 10⁴ scrypt guesses a second needs less than
     * about three centuries.
     */
    private const val WEAK_BELOW_LOG10 = 14.0

    /** The lists the words are drawn from (NOTICE), without words that sound alike. */
    private val lists: Map<String, List<String>> by lazy {
        listOf("de", "en").associateWith { language ->
            val stream =
                checkNotNull(javaClass.getResourceAsStream("words_$language.txt")) {
                    "no word list for $language"
                }
            stream
                .bufferedReader(Charsets.US_ASCII)
                .use { it.readLines() }
                .filter { it.isNotEmpty() }
        }
    }

    /** The German list for German, the English one for anything else. */
    fun words(locale: Locale): List<String> =
        lists.getValue(if (locale.language == "de") "de" else "en")

    private val random = SecureRandom()

    private val zxcvbn by lazy { Zxcvbn() }

    /** [WORDS] words in [locale]'s language, each drawn uniformly and on its own (PR-12a). */
    fun generate(locale: Locale): CharArray {
        val words = words(locale)
        val drawn = List(WORDS) { words[random.nextInt(words.size)] }
        val passphrase = CharArray(drawn.sumOf { it.length } + WORDS - 1) { ' ' }
        var at = 0
        for (word in drawn) {
            word.toCharArray(passphrase, at)
            at += word.length + 1
        }
        return passphrase
    }

    /**
     * Whether a passphrase a user chose deserves PR-12b's warning, judged on the device alone
     * (TR-25b). [context] is what an attacker would try first, such as the group's name.
     */
    fun isWeak(passphrase: CharArray, context: List<String> = emptyList()): Boolean =
        strength(passphrase, context) < 1f

    /** How far toward no longer [isWeak] a passphrase is, from 0 to 1, for showing as a bar. */
    fun strength(passphrase: CharArray, context: List<String> = emptyList()): Float {
        val strength = zxcvbn.measure(CharBuffer.wrap(passphrase), context)
        try {
            return (strength.guessesLog10 / WEAK_BELOW_LOG10).coerceIn(0.0, 1.0).toFloat()
        } finally {
            strength.wipe()
        }
    }
}
