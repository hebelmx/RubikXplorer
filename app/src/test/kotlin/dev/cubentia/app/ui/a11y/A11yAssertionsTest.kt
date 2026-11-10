package dev.cubentia.app.ui.a11y

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Guards the overflow detector itself, so a passing 200% audit is not vacuous. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class A11yAssertionsTest {
    @get:Rule val rule = createComposeRule()

    private val long = "A sentence long enough to need several lines at any font scale"

    @Test fun `short text in a wide column passes`() {
        rule.setContent { fontScale200 { Column { Text("Short") } } }
        rule.assertNoTextOverflow("short")
    }

    @Test fun `ellipsized text fails`() {
        rule.setContent { Text(long, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(120.dp)) }
        assertThrows(AssertionError::class.java) { rule.assertNoTextOverflow("ellipsis") }
    }

    @Test fun `text cut by a fixed height fails`() {
        rule.setContent { Text(long, modifier = Modifier.width(120.dp).height(20.dp)) }
        assertThrows(AssertionError::class.java) { rule.assertNoTextOverflow("fixed height") }
    }
}
