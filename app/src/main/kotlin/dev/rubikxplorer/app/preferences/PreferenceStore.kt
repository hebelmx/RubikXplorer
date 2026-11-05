package dev.rubikxplorer.app.preferences
import dev.rubikxplorer.app.data.SerialWriter
import dev.rubikxplorer.application.port.PreferencesLoad
import dev.rubikxplorer.application.port.PreferencesRepository
import dev.rubikxplorer.application.preference.Preference
import dev.rubikxplorer.application.preference.Preferences
import dev.rubikxplorer.application.preference.UpdatePreference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferenceStore private constructor(
    private val repository: PreferencesRepository?,
    private val writer: SerialWriter?,
    private val updater: UpdatePreference?,
) {
    @Inject
    constructor(repository: PreferencesRepository, writer: SerialWriter) : this(repository, writer, UpdatePreference(repository))
    constructor() : this(null, null, null)

    private val current = MutableStateFlow(Preferences.DEFAULT)
    val preferences: StateFlow<Preferences> = current.asStateFlow()
    private val loaded = CompletableDeferred<Unit>()

    /** Changes made before the load finished, re-applied over the loaded value so none is lost. */
    private val beforeLoad = mutableListOf<Preference>()
    private var resetBeforeLoad = false
    private var written = Preferences.DEFAULT

    init {
        if (writer == null) {
            loaded.complete(Unit)
        } else {
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                val result =
                    try {
                        writer.read {
                            (repository?.load() ?: PreferencesLoad.Failed).also {
                                if (it is PreferencesLoad.Found) {
                                    written =
                                        it.prefs
                                }
                            }
                        }
                    } catch (_: Exception) {
                        PreferencesLoad.Failed
                    }
                synchronized(this@PreferenceStore) {
                    if (result is PreferencesLoad.Found) {
                        val base = if (resetBeforeLoad) Preferences.DEFAULT else result.prefs
                        current.value = beforeLoad.fold(base) { prefs, change -> prefs.with(change) }
                    }
                    beforeLoad.clear()
                    loaded.complete(Unit)
                }
            }
        }
    }

    suspend fun awaitLoaded() {
        loaded.await()
    }

    @Synchronized fun update(preference: Preference) {
        if (!loaded.isCompleted) beforeLoad += preference
        current.value = current.value.with(preference)
        writer?.enqueue { updater?.invoke(written, preference)?.let { written = it.value } }
    }

    @Synchronized fun reset() {
        if (!loaded.isCompleted) {
            beforeLoad.clear()
            resetBeforeLoad = true
        }
        current.value = Preferences.DEFAULT
        writer?.enqueue {
            updater?.reset()
            written = Preferences.DEFAULT
        }
    }
}
