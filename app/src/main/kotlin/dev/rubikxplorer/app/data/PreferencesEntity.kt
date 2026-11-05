package dev.rubikxplorer.app.data
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import dev.rubikxplorer.application.preference.AnimationSpeed
import dev.rubikxplorer.application.preference.Preferences
import dev.rubikxplorer.application.preference.ThemeChoice
import dev.rubikxplorer.domain.tutor.TeachingMode

@Entity(tableName = "preferences")
data class PreferencesEntity(
    @PrimaryKey val id: Int = 1,
    val theme: String,
    val color_blind_glyphs: Boolean,
    val animation_speed: String,
    val haptics: Boolean,
    val reduce_motion: Boolean,
    val last_teaching_mode: String,
) {
    fun preferences(): Preferences =
        Preferences(
            ThemeChoice.entries.firstOrNull { it.name == theme } ?: ThemeChoice.SYSTEM,
            color_blind_glyphs,
            AnimationSpeed.entries.firstOrNull { it.name == animation_speed } ?: AnimationSpeed.NORMAL,
            haptics,
            reduce_motion,
            TeachingMode.entries.firstOrNull { it.name == last_teaching_mode } ?: TeachingMode.DEFAULT,
        )

    companion object {
        fun of(p: Preferences): PreferencesEntity =
            PreferencesEntity(
                1,
                p.theme.name,
                p.colorBlindGlyphs,
                p.animationSpeed.name,
                p.haptics,
                p.reduceMotion,
                p.lastTeachingMode.name,
            )
    }
}
