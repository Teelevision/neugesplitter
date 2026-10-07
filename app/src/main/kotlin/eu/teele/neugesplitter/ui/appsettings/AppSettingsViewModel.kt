package eu.teele.neugesplitter.ui.appsettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.teele.neugesplitter.Languages
import eu.teele.neugesplitter.Preferences
import eu.teele.neugesplitter.Theme
import eu.teele.neugesplitter.storage.GroupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The settings of the app as a whole, and removing everything it holds (UX-3, UX-5, PR-9). */
class AppSettingsViewModel(
    private val repository: GroupRepository,
    private val preferences: Preferences,
    private val languages: Languages,
) : ViewModel() {

    val theme: StateFlow<Theme> = preferences.theme

    private val _language = MutableStateFlow(languages.current)

    /** A BCP-47 tag, or null for the system's language. */
    val language: StateFlow<String?> = _language

    fun theme(theme: Theme) = preferences.theme(theme)

    fun language(tag: String?) {
        languages.current = tag
        _language.value = tag
    }

    /** Removes every group and setting from this device, then calls [onDone] (PR-9, TR-20). */
    fun deleteAll(onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.deleteAll() }
            preferences.clear()
            languages.current = null
            onDone()
        }
    }
}
