package dev.rubikxplorer.app.content

import dev.rubikxplorer.domain.content.ContentBundle
import dev.rubikxplorer.domain.content.ContentLoadError
import dev.rubikxplorer.domain.content.ContentLoadResult
import dev.rubikxplorer.domain.content.ContentLoader
import dev.rubikxplorer.domain.content.ContentSource
import dev.rubikxplorer.domain.content.Lesson
import dev.rubikxplorer.domain.content.LessonId
import dev.rubikxplorer.domain.content.Stage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A Lesson with the Stage that holds it. */
data class CatalogEntry(
    val lesson: Lesson,
    val stage: Stage,
)

/** What the [LessonCatalog] holds. */
sealed interface CatalogState {
    /** Not loaded yet. */
    data object Loading : CatalogState

    /** The [bundle] loaded; [lessons] in Stage then Lesson order, [strings] resolves its text. */
    data class Ready(
        val bundle: ContentBundle,
    ) : CatalogState {
        val lessons: List<CatalogEntry> = bundle.stages.flatMap { stage -> stage.lessons.map { CatalogEntry(it, stage) } }
        val strings: ContentStrings = ContentStrings(bundle.strings)

        fun find(id: LessonId): CatalogEntry? = lessons.firstOrNull { it.lesson.id == id }
    }

    /** The content did not load. */
    data class Failed(
        val error: ContentLoadError,
    ) : CatalogState
}

/**
 * The app's Lesson content (AD-6): the shipped bundle, loaded once, off the main thread, into a
 * sealed [CatalogState]. Loading never throws.
 */
@Singleton
class LessonCatalog internal constructor(
    private val loadContent: () -> ContentLoadResult,
    private val io: CoroutineDispatcher,
) {
    @Inject
    constructor(source: ContentSource) : this({ ContentLoader.load(source) }, Dispatchers.IO)

    private val current = MutableStateFlow<CatalogState>(CatalogState.Loading)
    private val loading = Mutex()

    val state: StateFlow<CatalogState> = current.asStateFlow()

    /** Loads the content unless it already is; safe to call from any number of callers. */
    suspend fun load() {
        loading.withLock {
            if (current.value != CatalogState.Loading) return
            val result = withContext(io) { loadContent() }
            current.value =
                when (result) {
                    is ContentLoadResult.Loaded -> CatalogState.Ready(result.bundle)
                    is ContentLoadResult.Failed -> CatalogState.Failed(result.error)
                }
        }
    }

    companion object {
        /** A catalog that already holds [result]; for tests and previews. */
        fun of(result: ContentLoadResult): LessonCatalog = LessonCatalog({ result }, Dispatchers.Unconfined)
    }
}
