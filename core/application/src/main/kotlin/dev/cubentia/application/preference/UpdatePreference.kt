package dev.cubentia.application.preference
import dev.cubentia.application.port.PreferenceSave
import dev.cubentia.application.port.PreferencesRepository

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
