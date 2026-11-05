package dev.rubikxplorer.application.preference
import dev.rubikxplorer.application.port.PreferenceSave
import dev.rubikxplorer.application.port.PreferencesRepository

data class PreferenceUpdate(
    val value: Preferences,
    val result: PreferenceSave,
)

class UpdatePreference(
    private val repository: PreferencesRepository,
) {
    suspend operator fun invoke(
        current: Preferences,
        preference: Preference,
    ): PreferenceUpdate {
        val next = current.with(preference)
        return PreferenceUpdate(next, repository.save(next))
    }

    suspend fun reset(): PreferenceUpdate = PreferenceUpdate(Preferences.DEFAULT, repository.reset())
}
