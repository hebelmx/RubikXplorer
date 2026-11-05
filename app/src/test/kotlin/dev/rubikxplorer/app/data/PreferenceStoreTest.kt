package dev.rubikxplorer.app.data
import dev.rubikxplorer.app.preferences.PreferenceStore
import dev.rubikxplorer.application.port.PreferenceSave
import dev.rubikxplorer.application.port.PreferencesLoad
import dev.rubikxplorer.application.port.PreferencesRepository
import dev.rubikxplorer.application.preference.Preference
import dev.rubikxplorer.application.preference.Preferences
import dev.rubikxplorer.application.preference.ThemeChoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferenceStoreTest {
    private class GatedRepository(
        val stored: Preferences,
    ) : PreferencesRepository {
        val gate = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()

        override suspend fun load(): PreferencesLoad {
            gate.await()
            return PreferencesLoad.Found(stored)
        }

        override suspend fun save(preferences: Preferences): PreferenceSave {
            writes += "save $preferences"
            return PreferenceSave.Saved
        }

        override suspend fun reset(): PreferenceSave {
            writes += "reset"
            return PreferenceSave.Saved
        }
    }

    @Test fun aResetBeforeTheLoadFinishesDropsTheStoredValue() =
        runBlocking {
            val stored = Preferences(theme = ThemeChoice.DARK, colorBlindGlyphs = true)
            for (resetFirst in listOf(true, false)) {
                val repo = GatedRepository(stored)
                val writer = SerialWriter()
                try {
                    val store = PreferenceStore(repo, writer)
                    if (resetFirst) {
                        store.reset()
                        store.update(Preference.Haptics(false))
                    } else {
                        store.update(Preference.Haptics(false))
                        store.reset()
                    }
                    repo.gate.complete(Unit)
                    store.awaitLoaded()
                    writer.flush()
                    val expected = if (resetFirst) Preferences(haptics = false) else Preferences.DEFAULT
                    assertEquals(expected, store.preferences.value)
                    val writes =
                        if (resetFirst) {
                            listOf("reset", "save ${Preferences(haptics = false)}")
                        } else {
                            listOf("save ${stored.copy(haptics = false)}", "reset")
                        }
                    assertEquals(writes, repo.writes)
                } finally {
                    writer.close()
                }
            }
        }

    @Test fun changesMadeBeforeTheLoadFinishesSurviveIt() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val stored = Preferences(theme = ThemeChoice.DARK, colorBlindGlyphs = true)
            val saved = mutableListOf<Preferences>()
            val repo =
                object : PreferencesRepository {
                    override suspend fun load(): PreferencesLoad {
                        gate.await()
                        return PreferencesLoad.Found(stored)
                    }

                    override suspend fun save(preferences: Preferences): PreferenceSave {
                        saved += preferences
                        return PreferenceSave.Saved
                    }

                    override suspend fun reset() = PreferenceSave.Saved
                }
            val writer = SerialWriter()
            try {
                val store = PreferenceStore(repo, writer)
                store.update(Preference.Haptics(false))
                assertEquals(false, store.preferences.value.haptics)
                gate.complete(Unit)
                store.awaitLoaded()
                writer.flush()
                assertEquals(stored.copy(haptics = false), store.preferences.value)
                assertEquals(listOf(stored.copy(haptics = false)), saved)
            } finally {
                writer.close()
            }
        }

    @Test fun immediateApplyOrderedWritesAndFailedSaveKeepsMemory() =
        runBlocking {
            val calls = mutableListOf<Preferences>()
            val repo =
                object : PreferencesRepository {
                    override suspend fun load() = PreferencesLoad.Failed

                    override suspend fun save(preferences: Preferences): PreferenceSave {
                        calls += preferences
                        return PreferenceSave.Failed
                    }

                    override suspend fun reset() = PreferenceSave.Saved
                }
            val writer = SerialWriter()
            try {
                val store = PreferenceStore(repo, writer)
                store.awaitLoaded()
                store.update(Preference.Theme(ThemeChoice.DARK))
                assertEquals(ThemeChoice.DARK, store.preferences.value.theme)
                store.update(Preference.Haptics(false))
                writer.flush()
                assertEquals(listOf(Preferences(theme = ThemeChoice.DARK), Preferences(theme = ThemeChoice.DARK, haptics = false)), calls)
                assertEquals(calls.last(), store.preferences.value)
                store.reset()
                assertEquals(Preferences.DEFAULT, store.preferences.value)
                writer.flush()
            } finally {
                writer.close()
            }
            PreferenceStore().awaitLoaded()
        }
}
