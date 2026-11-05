package dev.rubikxplorer.application.preference

import dev.rubikxplorer.domain.tutor.TeachingMode

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

enum class AnimationSpeed(
    val factor: Double,
) {
    SLOW(0.5),
    NORMAL(1.0),
    FAST(2.0),
    ;

    fun scale(baseMillis: Long): Long = (baseMillis / factor).toLong()
}

data class Preferences(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val colorBlindGlyphs: Boolean = false,
    val animationSpeed: AnimationSpeed = AnimationSpeed.NORMAL,
    val haptics: Boolean = true,
    val reduceMotion: Boolean = false,
    val lastTeachingMode: TeachingMode = TeachingMode.DEFAULT,
) {
    fun with(preference: Preference): Preferences =
        when (preference) {
            is Preference.Theme -> copy(theme = preference.value)
            is Preference.ColorBlindGlyphs -> copy(colorBlindGlyphs = preference.value)
            is Preference.Speed -> copy(animationSpeed = preference.value)
            is Preference.Haptics -> copy(haptics = preference.value)
            is Preference.ReduceMotion -> copy(reduceMotion = preference.value)
            is Preference.LastTeachingMode -> copy(lastTeachingMode = preference.value)
        }

    companion object {
        val DEFAULT = Preferences()
    }
}

sealed interface Preference {
    data class Theme(
        val value: ThemeChoice,
    ) : Preference

    data class ColorBlindGlyphs(
        val value: Boolean,
    ) : Preference

    data class Speed(
        val value: AnimationSpeed,
    ) : Preference

    data class Haptics(
        val value: Boolean,
    ) : Preference

    data class ReduceMotion(
        val value: Boolean,
    ) : Preference

    data class LastTeachingMode(
        val value: TeachingMode,
    ) : Preference
}
