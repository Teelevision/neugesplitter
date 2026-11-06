package eu.teele.neugesplitter.ui.editor

import eu.teele.neugesplitter.domain.category.BuiltInCategory
import eu.teele.neugesplitter.domain.category.CategoryModel
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The model `make categories` trained, as the app ships it. */
class CategoryAssetTest {

    private val model =
        CategoryModel(ByteBuffer.wrap(File("src/main/assets/categories.bin").readBytes()))

    @Test
    fun `the shipped model suggests in German and English alike (EN-13)`() {
        assertEquals(BuiltInCategory.HEALTH, model.guess("Zahnarzt"))
        assertEquals(BuiltInCategory.HEALTH, model.guess("dentist"))
        assertEquals(BuiltInCategory.TRAVEL, model.guess("Flug nach Lissabon"))
        assertEquals(BuiltInCategory.PETS, model.guess("dog food"))
    }

    @Test
    fun `the shipped model leaves gibberish alone (EN-13)`() {
        assertNull(model.guess("xq"))
    }
}
