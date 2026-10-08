package eu.teele.neugesplitter.ui.licences

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class LicencesScreenTest {

    @get:Rule val compose = createComposeRule()

    /**
     * The release build's `group:module`s, written by the releaseModules task; BOMs ship nothing.
     */
    private val shipped =
        javaClass.getResource("/release-modules.txt")!!.readText().lines().filter {
            it.isNotBlank() && !it.endsWith("-bom")
        }

    @Test
    fun `every module the release build ships has a notice`() {
        val unlisted = shipped.filter { module -> NOTICES.none { it.covers(module) } }
        assertEquals(emptyList(), unlisted)
    }

    @Test
    fun `every library notice covers a module the release build ships`() {
        val stale =
            NOTICES.filter { notice ->
                notice.modules.isNotEmpty() && shipped.none { notice.covers(it) }
            }
        assertEquals(emptyList(), stale)
    }

    @Test
    fun `every notice has its licence texts`() {
        for (file in NOTICES.flatMap { it.files }) {
            assertTrue(File("src/main/assets/licences/$file").isFile, file)
        }
        assertEquals(NOTICES.size, NOTICES.map { it.name }.toSet().size)
    }

    @Test
    fun `the list credits the word lists and opens a notice by name`() {
        var opened: String? = null
        compose.setContent {
            NeugesplitterTheme { LicencesScreen(onBack = {}, onOpen = { opened = it }) }
        }
        val german = hasText("CC BY 3.0 · German word list · adapted")
        compose.onNode(hasScrollAction()).performScrollToNode(german)
        compose.onNode(german).assertExists()
        compose.onNodeWithText("zxcvbn4j").performClick()
        assertEquals("zxcvbn4j", opened)
    }

    @Test
    fun `a licence shows its full text, and both of a dual licence`() {
        compose.setContent { NeugesplitterTheme { LicenceScreen("kage", onBack = {}) } }
        compose.waitUntil(5_000) {
            compose
                .onAllNodes(hasText("Copyright (c) 2021 The kage authors", substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNode(hasText("Apache License", substring = true)).assertExists()
    }

    @Test
    fun `unwrapping joins hard-wrapped lines and keeps paragraphs`() {
        assertEquals(
            listOf("Copyright 2026 Someone", "Permission is hereby granted, free of charge."),
            unwrap(
                "Copyright 2026 Someone\r\n\r\n  Permission is hereby\n  granted, free of charge.\n\n\n"
            ),
        )
    }

    private fun Notice.covers(module: String) = modules.any { module.startsWith(it) }
}
