package dev.cubentia.domain.tutor

import dev.cubentia.domain.text.Text

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

/**
 * How the Learner gets back on track after a mistake (FR-10): its [prompt] sentence and the
 * [offers] the lesson panel presents, in that order (the inline `RecoveryPrompt` renders them as buttons).
 */
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
    ;

    /** The one sentence that says how to get back. */
    val prompt: Text
        get() =
            when (this) {
                UNDO -> Text("msg.recovery.undo")
                GUIDED_RECOVERY -> Text("msg.recovery.guided-recovery")
                RETRY -> Text("msg.recovery.retry")
                REORIENT -> Text("msg.recovery.reorient")
                RE_ENTER -> Text("msg.recovery.re-enter")
            }

    /** What the Learner may choose, in the order offered; a turned cube is shown how, never undone. */
    val offers: List<RecoveryOffer>
        get() =
            when (this) {
                UNDO, GUIDED_RECOVERY, RETRY -> listOf(RecoveryOffer.UNDO, RecoveryOffer.GUIDE_ME_BACK, RecoveryOffer.TRY_AGAIN)
                REORIENT -> listOf(RecoveryOffer.SHOW_ME_HOW)
                RE_ENTER -> listOf(RecoveryOffer.RE_ENTER)
            }
}

/** One choice a Recovery Path offers the Learner (UX-DR25); the lesson panel renders them, never decides them. */
enum class RecoveryOffer {
    /** *Undo*: take back the last Move. */
    UNDO,

    /** *Guide me back*: the Guided recovery, offered while a plan can restore the preserved pieces. */
    GUIDE_ME_BACK,

    /** *Try again*: restart the Objective from the last Checkpoint. */
    TRY_AGAIN,

    /** *Show me how*: show how to hold the cube again. */
    SHOW_ME_HOW,

    /** Enter the cube again. */
    RE_ENTER,
}
