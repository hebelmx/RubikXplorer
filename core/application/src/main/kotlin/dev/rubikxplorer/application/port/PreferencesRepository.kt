package dev.rubikxplorer.application.port
import dev.rubikxplorer.application.preference.Preferences

interface PreferencesRepository {
    suspend fun load(): PreferencesLoad

    suspend fun save(preferences: Preferences): PreferenceSave

    suspend fun reset(): PreferenceSave
}

sealed interface PreferencesLoad {
    data class Found(
        val prefs: Preferences,
    ) : PreferencesLoad

    data object Failed : PreferencesLoad
}

sealed interface PreferenceSave {
    data object Saved : PreferenceSave

    data object Failed : PreferenceSave
}
