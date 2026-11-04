package dev.rubikxplorer.domain.tutor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/** Story 4.1 AC 1–2: the closed tutor vocabulary and the AD-5 [TutorDecision] field set. */
class TutorVocabularyTest {
    @Test
    fun `certainty is exactly verified, likely, unknown`() {
        assertEquals(listOf("VERIFIED", "LIKELY", "UNKNOWN"), Certainty.entries.map { it.name })
    }

    @Test
    fun `error category is exactly the seven AD-5 values`() {
        assertEquals(
            listOf(
                "WRONG_MOVE_OR_DIRECTION",
                "WRONG_PIECE",
                "WRONG_PIECE_ORIENTATION",
                "UNEXPECTED_ORIENTATION",
                "COMPLETED_LAYER_DISTURBED",
                "INVALID_ENTERED_STATE",
                "UNKNOWN_DISCREPANCY",
            ),
            ErrorCategory.entries.map { it.name },
        )
    }

    @Test
    fun `each error category has exactly one recovery path`() {
        val expected =
            mapOf(
                ErrorCategory.WRONG_MOVE_OR_DIRECTION to RecoveryPath.UNDO,
                ErrorCategory.WRONG_PIECE to RecoveryPath.UNDO,
                ErrorCategory.WRONG_PIECE_ORIENTATION to RecoveryPath.GUIDED_RECOVERY,
                ErrorCategory.UNEXPECTED_ORIENTATION to RecoveryPath.REORIENT,
                ErrorCategory.COMPLETED_LAYER_DISTURBED to RecoveryPath.GUIDED_RECOVERY,
                ErrorCategory.INVALID_ENTERED_STATE to RecoveryPath.RE_ENTER,
                ErrorCategory.UNKNOWN_DISCREPANCY to RecoveryPath.RETRY,
            )
        assertEquals(expected, ErrorCategory.entries.associateWith { it.recoveryPath })
        assertEquals(RecoveryPath.entries.toSet(), expected.values.toSet())
    }

    @Test
    fun `recovery path, next action and recovery variants are closed`() {
        assertEquals(listOf("UNDO", "GUIDED_RECOVERY", "RETRY", "REORIENT", "RE_ENTER"), RecoveryPath.entries.map { it.name })
        assertEquals(listOf("WATCH", "TURN", "NEXT_STEP", "FINISH"), NextAction.entries.map { it.name })
        assertEquals(
            setOf("Pending", "Ready", "Unavailable"),
            Recovery::class.java.declaredClasses
                .filter { Recovery::class.java.isAssignableFrom(it) }
                .map { it.simpleName }
                .toSet(),
        )
        assertEquals(42L, Recovery.Pending(42).effectId)
        assertEquals(TutorFixtures.moves("R"), Recovery.Ready(TutorFixtures.moves("R")).moves)
    }

    @Test
    fun `each recovery path has its prompt and offers, a turned cube offering show me how and no undo`() {
        assertEquals(
            mapOf(
                RecoveryPath.UNDO to "msg.recovery.undo",
                RecoveryPath.GUIDED_RECOVERY to "msg.recovery.guided-recovery",
                RecoveryPath.RETRY to "msg.recovery.retry",
                RecoveryPath.REORIENT to "msg.recovery.reorient",
                RecoveryPath.RE_ENTER to "msg.recovery.re-enter",
            ),
            RecoveryPath.entries.associateWith { it.prompt.key },
        )
        val back = listOf(RecoveryOffer.UNDO, RecoveryOffer.GUIDE_ME_BACK, RecoveryOffer.TRY_AGAIN)
        assertEquals(
            mapOf(
                RecoveryPath.UNDO to back,
                RecoveryPath.GUIDED_RECOVERY to back,
                RecoveryPath.RETRY to back,
                RecoveryPath.REORIENT to listOf(RecoveryOffer.SHOW_ME_HOW),
                RecoveryPath.RE_ENTER to listOf(RecoveryOffer.RE_ENTER),
            ),
            RecoveryPath.entries.associateWith { it.offers },
        )
        assertEquals(listOf("UNDO", "GUIDE_ME_BACK", "TRY_AGAIN", "SHOW_ME_HOW", "RE_ENTER"), RecoveryOffer.entries.map { it.name })
    }

    @Test
    fun `move feedback is exactly on path, progress, neutral and harmful, and harmful speaks through its diagnosis`() {
        assertEquals(listOf("ON_PATH", "PROGRESS", "NEUTRAL", "HARMFUL"), MoveFeedback.entries.map { it.name })
        assertEquals(
            listOf("msg.test-me.on-path", "msg.test-me.progress", "msg.test-me.neutral", null),
            MoveFeedback.entries.map { it.text?.key },
        )
        assertEquals(
            emptyList<String>(),
            MoveFeedback::class.java.declaredMethods
                .map { it.name }
                .filter { it == "classify" },
        )
    }

    @Test
    fun `tutor decision declares exactly the AD-5 fields`() {
        val fields =
            TutorDecision::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet()
        assertEquals(
            setOf(
                "objectiveId",
                "satisfied",
                "diagnosis",
                "certainty",
                "nextAction",
                "explanation",
                "recovery",
                "reveal",
                "advance",
                "feedback",
            ),
            fields,
        )
    }
}
