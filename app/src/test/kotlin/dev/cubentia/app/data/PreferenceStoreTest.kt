package dev.cubentia.app.data
import dev.cubentia.app.preferences.PreferenceStore
import dev.cubentia.application.port.PreferenceSave
import dev.cubentia.application.port.PreferencesLoad
import dev.cubentia.application.port.PreferencesRepository
import dev.cubentia.application.preference.PlaybackView
import dev.cubentia.application.preference.Preference
import dev.cubentia.application.preference.Preferences
import dev.cubentia.application.preference.ThemeChoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            val stored =
                Preferences(theme = ThemeChoice.DARK, colorBlindGlyphs = true, playbackView = PlaybackView.NET_2D, movePictures = false)
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

    @Test fun aStillUnreadableStoreIsNeverOverwrittenAndMemoryKeepsChanges() =
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
                // The disk value is unknown, so no write may put DEFAULT + change over it.
                assertEquals(emptyList<Preferences>(), calls)
                assertEquals(Preferences(theme = ThemeChoice.DARK, haptics = false), store.preferences.value)
                store.update(Preference.PlaybackViewChoice(PlaybackView.NET_2D))
                writer.flush()
                assertEquals(emptyList<Preferences>(), calls)
                assertEquals(PlaybackView.NET_2D, store.preferences.value.playbackView)
                store.update(Preference.MovePictures(false))
                assertEquals(false, store.preferences.value.movePictures)
                store.reset()
                assertEquals(Preferences.DEFAULT, store.preferences.value)
                writer.flush()
            } finally {
                writer.close()
            }
            PreferenceStore().awaitLoaded()
        }

    /** Fails the first [failures] loads, then finds [stored]. */
    private class FlakyRepository(
        val stored: Preferences,
        var failures: Int,
    ) : PreferencesRepository {
        val writes = mutableListOf<String>()

        override suspend fun load(): PreferencesLoad =
            if (failures > 0) {
                failures--
                PreferencesLoad.Failed
            } else {
                PreferencesLoad.Found(stored)
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

    @Test fun aFailedLoadThenARecoveringReadSavesTheStoredValueWithEveryChange() =
        runBlocking {
            val stored = Preferences(theme = ThemeChoice.DARK, colorBlindGlyphs = true, movePictures = false)
            // The initial load and the first write's retry fail; the second write's retry finds the stored value.
            val repo = FlakyRepository(stored, failures = 2)
            val writer = SerialWriter()
            try {
                val store = PreferenceStore(repo, writer)
                store.awaitLoaded()
                store.update(Preference.Haptics(false))
                writer.flush()
                assertEquals(emptyList<String>(), repo.writes)
                assertEquals(Preferences(haptics = false), store.preferences.value)
                store.update(Preference.PlaybackViewChoice(PlaybackView.NET_2D))
                store.update(Preference.Theme(ThemeChoice.LIGHT))
                writer.flush()
                val recovered = stored.copy(haptics = false, playbackView = PlaybackView.NET_2D)
                val all = recovered.copy(theme = ThemeChoice.LIGHT)
                assertEquals(listOf("save $recovered", "save $all"), repo.writes)
                assertEquals(all, store.preferences.value)
                assertTrue(repo.writes.none { it.contains("save ${Preferences(haptics = false)}") })
            } finally {
                writer.close()
            }
        }

    @Test fun aWriteQueuedBeforeAResetIsDroppedEvenWhenTheReadRecovers() =
        runBlocking {
            // Only the initial load fails; the queued write's retry would find the stored value.
            val repo = FlakyRepository(Preferences(theme = ThemeChoice.DARK), failures = 1)
            val writer = SerialWriter()
            try {
                val store = PreferenceStore(repo, writer)
                store.awaitLoaded()
                // Hold the writer until reset() is called, so the write is still queued when the reset arrives. Without
                // the gate the writer thread can run the write first, and its retried read then saves before any reset
                // exists (#271).
                val gate = CompletableDeferred<Unit>()
                writer.enqueue { gate.await() }
                store.update(Preference.Haptics(false))
                store.reset()
                gate.complete(Unit)
                writer.flush()
                assertEquals(listOf("reset"), repo.writes)
                assertEquals(Preferences.DEFAULT, store.preferences.value)
            } finally {
                writer.close()
            }
        }

    @Test fun aResetAfterAFailedLoadStillResetsTheRepository() =
        runBlocking {
            val stored = Preferences(theme = ThemeChoice.DARK)
            val repo = FlakyRepository(stored, failures = Int.MAX_VALUE)
            val writer = SerialWriter()
            try {
                val store = PreferenceStore(repo, writer)
                store.awaitLoaded()
                store.update(Preference.Haptics(false))
                store.reset()
                store.update(Preference.MovePictures(false))
                writer.flush()
                assertEquals(listOf("reset", "save ${Preferences(movePictures = false)}"), repo.writes)
                assertEquals(Preferences(movePictures = false), store.preferences.value)
            } finally {
                writer.close()
            }
        }
}
