package dev.rubikxplorer.domain.tutor

/**
 * What kind of mistake a Diagnosis names (AD-5, FR-9): a closed set. Every `when` over it is
 * exhaustive, and each category has exactly one [recoveryPath].
 */
enum class ErrorCategory {
    /** The right face, turned the wrong way or by the wrong amount; or a different face entirely. */
    WRONG_MOVE_OR_DIRECTION,

    /** A Move that worked on a piece other than the one the step targets. */
    WRONG_PIECE,

    /** The target piece is in its slot but twisted or flipped. */
    WRONG_PIECE_ORIENTATION,

    /** The whole cube is held in another orientation than the Lesson expects. */
    UNEXPECTED_ORIENTATION,

    /** A Move broke a layer completed earlier in the Lesson. */
    COMPLETED_LAYER_DISTURBED,

    /** The entered Cube State cannot exist. */
    INVALID_ENTERED_STATE,

    /** The evidence fits no rule. */
    UNKNOWN_DISCREPANCY,
    ;

    /** The one way back the tutor offers for this category. */
    val recoveryPath: RecoveryPath
        get() =
            when (this) {
                WRONG_MOVE_OR_DIRECTION -> RecoveryPath.UNDO
                WRONG_PIECE -> RecoveryPath.UNDO
                WRONG_PIECE_ORIENTATION -> RecoveryPath.GUIDED_RECOVERY
                UNEXPECTED_ORIENTATION -> RecoveryPath.REORIENT
                COMPLETED_LAYER_DISTURBED -> RecoveryPath.GUIDED_RECOVERY
                INVALID_ENTERED_STATE -> RecoveryPath.RE_ENTER
                UNKNOWN_DISCREPANCY -> RecoveryPath.RETRY
            }
}

/** How the Learner gets back on track after a mistake (FR-10). */
enum class RecoveryPath {
    /** Take back the last Move. */
    UNDO,

    /** Follow the shortest correction that restores the preserved pieces. */
    GUIDED_RECOVERY,

    /** Start the Objective again from the last Checkpoint. */
    RETRY,

    /** Hold the cube in the Lesson's orientation again. */
    REORIENT,

    /** Enter the cube's state again. */
    RE_ENTER,
}
