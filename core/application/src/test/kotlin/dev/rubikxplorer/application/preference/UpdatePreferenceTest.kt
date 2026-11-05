package dev.rubikxplorer.application.preference
import dev.rubikxplorer.application.port.PreferenceSave
import dev.rubikxplorer.application.port.PreferencesLoad
import dev.rubikxplorer.application.port.PreferencesRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class UpdatePreferenceTest {
    @Test fun writerReturnsNewValueEvenOnFailureAndResetIsScoped() =
        runBlocking {
            val writes = mutableListOf<Preferences>()
            var resets = 0
            var result: PreferenceSave = PreferenceSave.Saved
            val repository =
                object : PreferencesRepository {
                    override suspend fun load() = PreferencesLoad.Found(Preferences.DEFAULT)

                    override suspend fun save(preferences: Preferences): PreferenceSave {
                        writes += preferences
                        return result
                    }

                    override suspend fun reset(): PreferenceSave {
                        resets++
                        return result
                    }
                }
            val update = UpdatePreference(repository)
            val first = update(Preferences.DEFAULT, Preference.Theme(ThemeChoice.DARK))
            assertEquals(PreferenceSave.Saved, first.result)
            result = PreferenceSave.Failed
            val failed = update(first.value, Preference.Haptics(false))
            assertFalse(failed.value.haptics)
            assertEquals(ThemeChoice.DARK, failed.value.theme)
            assertEquals(PreferenceSave.Failed, failed.result)
            assertEquals(listOf(first.value, failed.value), writes)
            assertEquals(PreferenceUpdate(Preferences.DEFAULT, PreferenceSave.Failed), update.reset())
            assertEquals(1, resets)
        }
}
