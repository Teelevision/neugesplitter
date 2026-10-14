package eu.teele.neugesplitter

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which colour scheme the app uses (UX-3). */
enum class Theme {
    SYSTEM,
    LIGHT,
    DARK,
}

/** The app's own language, apart from the system's (UX-5). */
interface Languages {
    /** A BCP-47 tag such as "en" or "de"; null follows the system. */
    var current: String?
}

/** [Languages] kept by the system, which also applies the choice to every activity. */
class SystemLanguages(context: Context) : Languages {

    private val manager = context.getSystemService(LocaleManager::class.java)

    override var current: String?
        get() = manager.applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language
        set(value) {
            manager.applicationLocales =
                if (value == null) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(value)
        }
}

/** Settings of this installation that no group depends on. */
class Preferences(context: Context) {

    private val store = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)

    private val _theme =
        MutableStateFlow(
            Theme.entries.firstOrNull { it.name == store.getString(THEME, null) } ?: Theme.SYSTEM
        )

    val theme: StateFlow<Theme> = _theme

    fun theme(theme: Theme) {
        store.edit().putString(THEME, theme.name).apply()
        _theme.value = theme
    }

    /** The group open when the app was last left, as hex, which a fresh start opens again. */
    var lastGroup: String?
        get() = store.getString(LAST_GROUP, null)
        set(value) = store.edit().putString(LAST_GROUP, value).apply()

    /** Whether the app has asked to post notifications, which it does once (SY-10). */
    var askedToNotify: Boolean
        get() = store.getBoolean(ASKED_TO_NOTIFY, false)
        set(value) = store.edit().putBoolean(ASKED_TO_NOTIFY, value).apply()

    /** Forgets every setting. */
    fun clear() {
        store.edit().clear().apply()
        _theme.value = Theme.SYSTEM
    }

    private companion object {
        const val THEME = "theme"
        const val LAST_GROUP = "last_group"
        const val ASKED_TO_NOTIFY = "asked_to_notify"
    }
}
