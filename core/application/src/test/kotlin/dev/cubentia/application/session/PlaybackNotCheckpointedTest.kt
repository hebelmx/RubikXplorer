package dev.cubentia.application.session

import dev.cubentia.domain.playback.PlaybackController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/** AC 1 (AD-20): playback is never part of session state, events or effects, and is not serializable. */
class PlaybackNotCheckpointedTest {
    private val playbackPackage = PlaybackController::class.java.packageName

    /** [root] and every nested class assignable to it (the sealed variants). */
    private fun family(root: Class<*>): List<Class<*>> =
        listOf(root) + root.declaredClasses.filter { root.isAssignableFrom(it) && it != root }

    private fun types(type: Type): List<Class<*>> =
        when (type) {
            is Class<*> -> listOf(type)
            is ParameterizedType -> types(type.rawType) + type.actualTypeArguments.flatMap { types(it) }
            else -> emptyList()
        }

    private fun fields(c: Class<*>): List<Field> = c.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }

    @Test
    fun `no session state, event or effect property holds a playback type`() {
        val classes =
            family(SessionState::class.java) + family(SessionEvent::class.java) + family(Effect::class.java) +
                EffectResultDiscarded::class.java
        assertTrue(classes.size >= 1 + 8 + 2 + 1, "found only $classes")
        val hits =
            classes.flatMap { c ->
                fields(c)
                    .filter { f -> types(f.genericType).any { it.packageName == playbackPackage } }
                    .map { "${c.simpleName}.${it.name}" }
            }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun `the scan sees a playback field`() {
        class Holder(
            @Suppress("unused") val controllers: List<PlaybackController>,
        )
        assertTrue(fields(Holder::class.java).any { f -> types(f.genericType).any { it.packageName == playbackPackage } })
    }

    @Test
    fun `the controller has no generated serializer`() {
        val c = PlaybackController::class.java
        assertFalse(c.declaredClasses.any { it.simpleName == "\$serializer" })
        assertFalse(
            runCatching { Class.forName("${c.name}\$\$serializer") }.isSuccess,
            "PlaybackController must not be @Serializable",
        )
        assertFalse(c.declaredMethods.any { it.name == "serializer" })
        assertFalse(PlaybackController.Companion::class.java.declaredMethods.any { it.name == "serializer" })
    }
}
