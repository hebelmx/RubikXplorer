package dev.cubentia.application.preference
import dev.cubentia.domain.tutor.TeachingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PreferencesTest {
    @Test fun fieldsAndSpeed() {
        val p =
            Preferences.DEFAULT
                .with(Preference.Theme(ThemeChoice.DARK))
                .with(Preference.ColorBlindGlyphs(true))
                .with(Preference.Speed(AnimationSpeed.FAST))
                .with(Preference.Haptics(false))
                .with(Preference.ReduceMotion(true))
                .with(Preference.LastTeachingMode(TeachingMode.LET_ME_TRY))
        assertEquals(Preferences(ThemeChoice.DARK, true, AnimationSpeed.FAST, false, true, TeachingMode.LET_ME_TRY), p)
        assertEquals(Preferences(), Preferences.DEFAULT)
        assertEquals(listOf(400L, 200L, 100L), AnimationSpeed.entries.map { it.scale(200) })
    }
}
