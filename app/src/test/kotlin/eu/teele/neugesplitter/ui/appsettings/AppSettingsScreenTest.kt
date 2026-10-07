package eu.teele.neugesplitter.ui.appsettings

import android.os.Looper
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.teele.neugesplitter.Languages
import eu.teele.neugesplitter.Preferences
import eu.teele.neugesplitter.Theme
import eu.teele.neugesplitter.domain.group.Group
import eu.teele.neugesplitter.domain.group.Member
import eu.teele.neugesplitter.domain.value.EntryDate
import eu.teele.neugesplitter.domain.value.EventId
import eu.teele.neugesplitter.domain.value.GroupId
import eu.teele.neugesplitter.domain.value.MemberId
import eu.teele.neugesplitter.storage.Database
import eu.teele.neugesplitter.storage.GroupRepository
import eu.teele.neugesplitter.ui.theme.NeugesplitterTheme
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private class FakeLanguages(override var current: String? = null) : Languages

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w411dp-h891dp")
class AppSettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private val today = EntryDate.parse("2026-10-07")
    private val at = Instant.parse("2026-10-07T12:00:00Z")
    private var ids = 0
    private var deleted = 0

    private fun id(n: Int) = "%032x".format(n)

    private fun eventId() = EventId.parse(id(0x100 + ids++))

    private val groups =
        GroupRepository(
            AndroidSqliteDriver(Database.Schema, RuntimeEnvironment.getApplication(), null)
        )
    private val preferences = Preferences(RuntimeEnvironment.getApplication())
    private val languages = FakeLanguages()

    private fun seed(group: Int, name: String) {
        val anna = Member(MemberId.parse(id(group + 1)), "Anna")
        val created =
            Group.create(
                GroupId.parse(id(group)),
                name,
                null,
                null,
                null,
                listOf(anna),
                at,
                ::eventId,
            )
        groups.create(created, today)
    }

    private fun show() {
        val viewModel = AppSettingsViewModel(groups, preferences, languages)
        compose.setContent {
            NeugesplitterTheme {
                AppSettingsScreen(
                    viewModel,
                    version = "1.2.3",
                    onBack = {},
                    onDeleted = { deleted++ },
                )
            }
        }
    }

    private fun awaitGone(text: String) =
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }

    /** A radio row of the open dialog; the settings rows show the same words. */
    private fun choose(label: String) =
        compose.onNode(hasText(label) and isSelectable()).performClick()

    private fun typeInto(text: String) =
        compose.onNode(hasSetTextAction()).performTextReplacement(text)

    @Test
    fun `choosing dark saves the theme and shows it (UX-3)`() {
        show()
        compose.onNodeWithText("Theme").performClick()
        choose("Dark")
        awaitGone("Cancel")

        compose.onNodeWithText("Dark").assertExists()
        assertEquals(Theme.DARK, Preferences(RuntimeEnvironment.getApplication()).theme.value)
    }

    @Test
    fun `choosing a language sets it, and the system default clears it (UX-5)`() {
        show()
        compose.onAllNodesWithText("Language").onLast().performClick()
        choose("Deutsch")
        awaitGone("Cancel")
        compose.onNodeWithText("Deutsch").assertExists()
        assertEquals("de", languages.current)

        compose.onAllNodesWithText("Language").onLast().performClick()
        choose("System default")
        awaitGone("Cancel")
        assertNull(languages.current)
    }

    @Test
    fun `choosing the language already in use only closes the dialog (UX-5)`() {
        languages.current = "en"
        show()
        compose.onAllNodesWithText("Language").onLast().performClick()
        choose("English")
        awaitGone("Cancel")
        assertEquals("en", languages.current)
    }

    @Test
    fun `the version and the licence are shown`() {
        show()
        compose.onNodeWithText("1.2.3").assertExists()
        compose.onNodeWithText("MIT · © 2026 Marius Neugebauer").assertExists()
    }

    @Test
    fun `deleting everything needs the word typed and cancelling collapses it (PR-9)`() {
        show()
        compose.onNodeWithText("Delete all local data").performClick()
        compose.onNodeWithText("Delete everything").assertIsNotEnabled()
        typeInto("delet")
        compose.onNodeWithText("Delete everything").assertIsNotEnabled()
        typeInto("  delete ")
        compose.onNodeWithText("Delete everything").assertIsEnabled()

        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Delete everything").assertDoesNotExist()
        compose.onNodeWithText("Delete all local data").performClick()
        compose.onNodeWithText("Delete everything").assertIsNotEnabled()
    }

    @Test
    fun `confirming removes every group and setting and calls back (PR-9)`() {
        seed(0x10, "Trip")
        seed(0x20, "Flat")
        preferences.theme(Theme.DARK)
        languages.current = "de"
        for (group in listOf(0x10, 0x20)) assertNotNull(groups.load(GroupId.parse(id(group))))
        show()

        compose.onNodeWithText("Delete all local data").performClick()
        typeInto("DELETE")
        compose.onNodeWithText("Delete everything").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            deleted > 0
        }

        assertEquals(1, deleted)
        for (group in listOf(0x10, 0x20)) assertNull(groups.load(GroupId.parse(id(group))))
        assertEquals(Theme.SYSTEM, Preferences(RuntimeEnvironment.getApplication()).theme.value)
        assertNull(languages.current)
    }
}
