package dev.cubentia.domain.text

/**
 * Learner-facing text as a content key plus arguments, resolved against a string bundle by the UI.
 *
 * Keys take the form `<area>.<contentId>.<field>[.<n>]`; domain messages live under `msg.*`
 * (for example `msg.validation.parity`).
 */
data class Text(
    val key: String,
    val args: Map<String, Any> = emptyMap(),
)
