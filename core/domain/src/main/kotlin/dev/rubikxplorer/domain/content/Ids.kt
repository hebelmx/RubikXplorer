package dev.rubikxplorer.domain.content

import kotlinx.serialization.Serializable

/** A Stage ID, `stage-NN-<slug>` (for example `stage-02-first-cross`); stable and never reused once shipped. */
@Serializable
@JvmInline
value class StageId(
    val value: String,
) {
    /** True when [value] has the `stage-NN-<slug>` form. */
    val isWellFormed: Boolean get() = FORMAT.matches(value)

    override fun toString(): String = value

    private companion object {
        val FORMAT = Regex("stage-\\d{2}-[a-z0-9]+(-[a-z0-9]+)*")
    }
}

/** A Lesson ID, `lesson-NN-NN` (for example `lesson-02-03`); stable and never reused once shipped. */
@Serializable
@JvmInline
value class LessonId(
    val value: String,
) {
    /** True when [value] has the `lesson-NN-NN` form. */
    val isWellFormed: Boolean get() = FORMAT.matches(value)

    override fun toString(): String = value

    private companion object {
        val FORMAT = Regex("lesson-\\d{2}-\\d{2}")
    }
}

/** A Recovery Path reference, `recovery-<slug>`; resolved by Epic 4. */
@Serializable
@JvmInline
value class RecoveryRef(
    val value: String,
) {
    /** True when [value] has the `recovery-<slug>` form. */
    val isWellFormed: Boolean get() = FORMAT.matches(value)

    override fun toString(): String = value

    private companion object {
        val FORMAT = Regex("recovery-[a-z0-9]+(-[a-z0-9]+)*")
    }
}

/**
 * A key into `content/strings/en.json`, of the form `<area>.<contentId>.<field>[.<n>]`, where
 * `contentId` is the ID of the Stage or Lesson that owns the text. Content keys carry no arguments.
 */
@Serializable
@JvmInline
value class TextKey(
    val value: String,
) {
    /** The `contentId` segment, or null when [value] is not of the key form. */
    val contentId: String? get() = FORMAT.matchEntire(value)?.groupValues?.get(CONTENT_ID_GROUP)

    override fun toString(): String = value

    private companion object {
        const val CONTENT_ID_GROUP = 1
        val FORMAT = Regex("[a-z][a-z0-9-]*\\.([a-z0-9-]+)\\.[a-z][a-z0-9-]*(\\.\\d+)?")
    }
}
