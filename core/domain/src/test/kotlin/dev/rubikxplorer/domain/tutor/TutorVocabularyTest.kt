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
