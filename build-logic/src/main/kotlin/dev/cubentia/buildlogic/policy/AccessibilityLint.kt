package dev.cubentia.buildlogic.policy

/**
 * AD-14: Android lint's accessibility checks run at `error` severity so `build` fails on a new
 * finding (NFR-7, Story 12.1). Fix findings at the source; no lint baseline and no `@SuppressLint`
 * or `tools:ignore` for these ids.
 */
object AccessibilityLint {
    val ERROR_ISSUES =
        setOf("ContentDescription", "ClickableViewAccessibility", "KeyboardInaccessibleWidget", "LabelFor", "GetContentDescriptionOverride")
}
