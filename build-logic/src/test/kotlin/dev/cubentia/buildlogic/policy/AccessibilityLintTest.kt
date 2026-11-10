package dev.cubentia.buildlogic.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AccessibilityLintTest {
    @Test
    fun `accessibility issues are errors`() {
        assertEquals(
            setOf(
                "ContentDescription",
                "ClickableViewAccessibility",
                "KeyboardInaccessibleWidget",
                "LabelFor",
                "GetContentDescriptionOverride",
            ),
            AccessibilityLint.ERROR_ISSUES,
        )
    }
}
