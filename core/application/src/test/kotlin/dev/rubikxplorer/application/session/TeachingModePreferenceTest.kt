package dev.rubikxplorer.application.session
import dev.rubikxplorer.application.preference.Preference
import dev.rubikxplorer.domain.tutor.TeachingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TeachingModePreferenceTest {
    @Test fun changesEmitExactlyOnceAndStartHonorsOptionalMode() {
        val event = lessonStarted(whiteCrossLesson())
        val initial = reduce(SessionState.IDLE, event.copy(mode = TeachingMode.LET_ME_TRY)).state
        assertEquals(TeachingMode.LET_ME_TRY, initial.teachingMode)
        assertEquals(initial.teachingMode, reduce(initial, event.copy(mode = null)).state.teachingMode)
        assertTrue(reduce(initial, modeChanged(initial.teachingMode)).effects.isEmpty())
        val changed = reduce(initial, modeChanged(TeachingMode.TEST_ME))
        assertEquals(
            listOf(Effect.UpdatePreference(initial.nextEffectId, initial.revision, Preference.LastTeachingMode(TeachingMode.TEST_ME))),
            changed.effects,
        )
        assertEquals(initial.nextEffectId + 1, changed.state.nextEffectId)
    }
}
