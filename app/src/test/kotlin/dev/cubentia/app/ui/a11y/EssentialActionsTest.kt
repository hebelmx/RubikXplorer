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
class EssentialActionsTest {
    @get:Rule val rule = createComposeRule()

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun compact() = auditScreens(rule, largeType = false)

    @Test
    @Config(qualifiers = "w900dp-h891dp")
    fun expanded() = auditScreens(rule, largeType = false, expanded = true)
}
