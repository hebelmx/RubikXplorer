package dev.cubentia.app.data
import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import dev.cubentia.application.preference.AnimationSpeed
import dev.cubentia.application.preference.Preferences
import dev.cubentia.application.preference.ThemeChoice
import dev.cubentia.domain.tutor.TeachingMode

@Entity(tableName = "preferences")
data class PreferencesEntity(
    @PrimaryKey val id: Int = 1,
    val theme: String,
    val color_blind_glyphs: Boolean,
    val animation_speed: String,
    val haptics: Boolean,
    val reduce_motion: Boolean,
    val last_teaching_mode: String,
    @ColumnInfo(defaultValue = "0") val onboarded: Boolean = false,
) {
    fun preferences(): Preferences =
        Preferences(
            ThemeChoice.entries.firstOrNull { it.name == theme } ?: ThemeChoice.SYSTEM,
            color_blind_glyphs,
            AnimationSpeed.entries.firstOrNull { it.name == animation_speed } ?: AnimationSpeed.NORMAL,
            haptics,
            reduce_motion,
            TeachingMode.entries.firstOrNull { it.name == last_teaching_mode } ?: TeachingMode.DEFAULT,
            onboarded,
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
                p.onboarded,
            )
    }
}
