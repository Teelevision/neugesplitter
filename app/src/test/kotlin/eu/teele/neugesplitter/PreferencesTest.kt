package eu.teele.neugesplitter

import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PreferencesTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `the theme follows the system until one is chosen (UX-3)`() {
        assertEquals(Theme.SYSTEM, Preferences(context).theme.value)
    }

    @Test
    fun `a chosen theme is kept across instances (UX-3)`() {
        val preferences = Preferences(context)
        preferences.theme(Theme.LIGHT)
        assertEquals(Theme.LIGHT, preferences.theme.value)
        assertEquals(Theme.LIGHT, Preferences(context).theme.value)
    }

    @Test
    fun `clearing returns the theme to the system's (PR-9)`() {
        val preferences = Preferences(context)
        preferences.theme(Theme.DARK)
        preferences.clear()
        assertEquals(Theme.SYSTEM, preferences.theme.value)
        assertEquals(Theme.SYSTEM, Preferences(context).theme.value)
    }

    @Test
    fun `an unknown stored theme reads as the system's (UX-3)`() {
        context
            .getSharedPreferences("preferences", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("theme", "SEPIA")
            .commit()
        assertEquals(Theme.SYSTEM, Preferences(context).theme.value)
    }
}
