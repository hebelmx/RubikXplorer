package dev.rubikxplorer.app.data
import dev.rubikxplorer.application.port.PreferenceSave
import dev.rubikxplorer.application.port.PreferencesLoad
import dev.rubikxplorer.application.port.PreferencesRepository
import dev.rubikxplorer.application.preference.Preferences
import kotlinx.coroutines.CancellationException

class RoomPreferencesRepository(
    private val database: RubikDatabase,
) : PreferencesRepository {
    override suspend fun load(): PreferencesLoad =
        try {
            PreferencesLoad.Found(database.preferences().load()?.preferences() ?: Preferences.DEFAULT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PreferencesLoad.Failed
        }

    override suspend fun save(preferences: Preferences): PreferenceSave =
        attempt {
            database.preferences().save(PreferencesEntity.of(preferences))
        }

    override suspend fun reset(): PreferenceSave = attempt { database.preferences().reset() }

    private suspend fun attempt(block: suspend () -> Unit): PreferenceSave =
        try {
            block()
            PreferenceSave.Saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PreferenceSave.Failed
        }
}
