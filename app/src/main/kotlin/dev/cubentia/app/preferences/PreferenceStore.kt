package dev.cubentia.app.preferences
import dev.cubentia.app.data.SerialWriter
import dev.cubentia.application.port.PreferencesLoad
import dev.cubentia.application.port.PreferencesRepository
import dev.cubentia.application.preference.Preference
import dev.cubentia.application.preference.Preferences
import dev.cubentia.application.preference.UpdatePreference
import kotlinx.coroutines.CancellationException
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
    private val writer: SerialWriter?,
    private val repository: PreferencesRepository?,
    private val updater: UpdatePreference?,
) {
    /** The app's store: [updater] is the injected single writer of [repository]. */
    @Inject
    constructor(repository: PreferencesRepository, writer: SerialWriter, updater: UpdatePreference) : this(writer, repository, updater)

    /** A store over [repository] with its own [UpdatePreference], for tests. */
    constructor(repository: PreferencesRepository, writer: SerialWriter) : this(writer, repository, UpdatePreference(repository))
    constructor() : this(null, null, null)

    private val current = MutableStateFlow(Preferences.DEFAULT)
    val preferences: StateFlow<Preferences> = current.asStateFlow()
    private val loaded = CompletableDeferred<Unit>()

    /** Changes made before the load finished, re-applied over the loaded value so none is lost. */
    private val beforeLoad = mutableListOf<Preference>()
    private var resetBeforeLoad = false
    private var written = Preferences.DEFAULT

    /**
     * Whether [written] is the value on disk. After a failed load it is not: a write then retries the read and saves only
     * over a found value, so an unreadable store is never overwritten with defaults.
     */
    private var known = false

    /** Changes made while the disk value is unknown, in order; folded over the value a retried read finds. */
    private val unknownChanges = mutableListOf<Preference>()

    /**
     * Bumped by a [reset] made while the disk value is unknown: a retried write queued before it is obsolete, since the
     * reset writes over it.
     */
    private var generation = 0

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
                                    synchronized(this@PreferenceStore) {
                                        known = true
                                        unknownChanges.clear()
                                    }
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
        if (writer == null) return
        val upTo =
            if (known) {
                0
            } else {
                unknownChanges += preference
                unknownChanges.size
            }
        val queuedIn = generation
        writer.enqueue {
            if (synchronized(this) { known }) {
                updater?.invoke(written, preference)?.let { written = it.value }
            } else {
                saveOverRetriedLoad(upTo, queuedIn)
            }
        }
    }

    /**
     * Runs in the writer: retries the read and, on a found value, saves it folded with the first [upTo] unknown changes
     * (the last is this write's own change) through the single writer [updater].
     */
    private suspend fun saveOverRetriedLoad(
        upTo: Int,
        queuedIn: Int,
    ) {
        if (synchronized(this) { generation != queuedIn }) return
        val load =
            try {
                repository?.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        val found = (load as? PreferencesLoad.Found)?.prefs ?: return
        val (before, change) =
            synchronized(this) {
                if (generation != queuedIn || known) return
                unknownChanges.take(upTo - 1).fold(found) { prefs, change -> prefs.with(change) } to unknownChanges[upTo - 1]
            }
        val base = updater?.invoke(before, change)?.value ?: return
        synchronized(this) {
            if (generation != queuedIn || known) return
            written = base
            known = true
            // Changes made after this write stay applied in memory; their own queued writes save them next.
            current.value = unknownChanges.drop(upTo).fold(base) { prefs, change -> prefs.with(change) }
            unknownChanges.clear()
        }
    }

    @Synchronized fun reset() {
        if (!loaded.isCompleted) {
            beforeLoad.clear()
            resetBeforeLoad = true
        }
        current.value = Preferences.DEFAULT
        if (!known) {
            generation++
            unknownChanges.clear()
        }
        writer?.enqueue {
            updater?.reset()
            synchronized(this) {
                written = Preferences.DEFAULT
                known = true
                unknownChanges.clear()
            }
        }
    }
}
