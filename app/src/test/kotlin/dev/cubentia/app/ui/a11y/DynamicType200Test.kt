package dev.cubentia.app.ui.a11y

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DynamicType200Test {
    @get:Rule val rule = createComposeRule()

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun compact() = auditScreens(rule, largeType = true)

    @Test
    @Config(qualifiers = "w900dp-h891dp")
    fun expanded() = auditScreens(rule, largeType = true, expanded = true)

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun compactInteractionStates() = auditInteractionStates(rule, expanded = false)

    @Test
    @Config(qualifiers = "w900dp-h891dp")
    fun expandedInteractionStates() = auditInteractionStates(rule, expanded = true)

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun compactEditorStates() = auditEditorStates(rule)

    @Test
    @Config(qualifiers = "w900dp-h891dp")
    fun expandedEditorStates() = auditEditorStates(rule)
}
