package eu.teele.neugesplitter.domain.category

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CategoryModelTest {

    /** Each word stands for one label, in a space of its own; n-grams tell nothing. */
    private val model =
        CategoryModel(
            ByteBuffer.wrap(
                CategoryModel.encode(
                    CategoryModel.Parts(
                        labels = listOf("health", "transfer", "groceries", "retired"),
                        threshold = 0.5f,
                        words = listOf("zahnarzt", "überweisung", "brot"),
                        dimension = 3,
                        wordVectors = byteArrayOf(127, 0, 0, 0, 127, 0, 0, 0, 127),
                        wordScale = 1 / 127f,
                        buckets = 4,
                        gramDimension = 1,
                        shortest = 3,
                        longest = 5,
                        gramVectors = ByteArray(4),
                        gramScale = 1f,
                        output =
                            floatArrayOf(
                                10f,
                                0f,
                                0f,
                                0f,
                                0f,
                                10f,
                                0f,
                                0f,
                                0f,
                                0f,
                                10f,
                                0f,
                                0f,
                                0f,
                                0f,
                                0f,
                            ),
                        bias = floatArrayOf(0f, 0f, 0f, -10f),
                    )
                )
            )
        )

    @Test
    fun `a model reads back as it was written, a label no longer built in as none`() {
        assertEquals(
            listOf(
                BuiltInCategory.HEALTH,
                BuiltInCategory.TRANSFER,
                BuiltInCategory.GROCERIES,
                null,
            ),
            model.labels,
        )
        assertEquals(0.5f, model.threshold)
    }

    @Test
    fun `a description's words decide its category, in any case and form (EN-13)`() {
        assertEquals(BuiltInCategory.HEALTH, model.guess("Zahnarzt"))
        assertEquals(BuiltInCategory.GROCERIES, model.guess("BROT!"))
        assertEquals(1f, model.probabilities("Zahnarzt").sum(), 1e-5f)
    }

    @Test
    fun `nothing is guessed below the threshold (EN-13)`() {
        assertNull(model.guess("Zahnarzt und Brot"))
        assertNull(model.guess("Kino"))
        assertNull(model.guess(""))
    }

    @Test
    fun `a transfer is never guessed (EN-13)`() {
        assertNull(model.guess("Überweisung"))
    }

    @Test
    fun `anything else is not a model`() {
        assertFailsWith<IllegalArgumentException> { CategoryModel(ByteBuffer.wrap(ByteArray(64))) }
    }

    @Test
    fun `words are split at everything but letters and digits, in NFKC and lower case`() {
        assertEquals(listOf("kaffee", "kuchen", "2"), words("Kaffee & Kuchen (2)"))
        assertEquals(listOf("rewe"), words("ＲＥＷＥ"))
        assertEquals(listOf("café"), words("Cafe\u0301"))
    }

    @Test
    fun `a word's n-grams are its marked whole and its parts of three to five`() {
        val expected = listOf("<ab>", "<ab", "ab>").map { fnv(it.encodeToByteArray()) and 1023 }
        assertEquals(expected, ngrams("ab", 3, 5, 1024).toList())
    }

    @Test
    fun `hashes are 32-bit FNV-1a`() {
        assertEquals(0x811c9dc5.toInt(), fnv(ByteArray(0)))
        assertEquals(0xe40c292c.toInt(), fnv("a".encodeToByteArray()))
    }
}
