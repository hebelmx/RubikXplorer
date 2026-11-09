package dev.cubentia.domain.content

/**
 * Reads the top-level argument names of an ICU message (`{name}`, `{name, plural, …}`), honouring
 * ICU apostrophe quoting. Text inside sub-messages (nested arguments, `#`) is skipped, not reported.
 */
object IcuArguments {
    private const val QUOTE = '\''
    private const val SYNTAX = "{}#|"
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_-]*|\\d+")

    /** The top-level argument names of [message], or [IcuResult.Malformed] when its braces do not balance. */
    fun parse(message: String): IcuResult {
        val names = linkedSetOf<String>()
        var i = 0
        while (i < message.length) {
            val char = message[i]
            when {
                char == QUOTE -> {
                    i = skipQuote(message, i)
                }

                char == '}' -> {
                    return IcuResult.Malformed(i)
                }

                char == '{' -> {
                    val end = matchingBrace(message, i) ?: return IcuResult.Malformed(i)
                    val name = message.substring(i + 1, end).substringBefore(',').trim()
                    if (!NAME.matches(name)) return IcuResult.Malformed(i)
                    names += name
                    i = end + 1
                }

                else -> {
                    i++
                }
            }
        }
        return IcuResult.Parsed(names)
    }

    /** Index just past the quoted literal (or `''`) starting at [start]; a lone apostrophe is literal. */
    private fun skipQuote(
        message: String,
        start: Int,
    ): Int {
        val next = message.getOrNull(start + 1) ?: return start + 1
        if (next == QUOTE) return start + 2
        if (next !in SYNTAX) return start + 1
        var i = start + 1
        while (i < message.length) {
            if (message[i] == QUOTE) {
                if (message.getOrNull(i + 1) == QUOTE) {
                    i += 2
                    continue
                }
                return i + 1
            }
            i++
        }
        return i
    }

    /** Index of the `}` closing the `{` at [open], or null when it never closes. */
    private fun matchingBrace(
        message: String,
        open: Int,
    ): Int? {
        var depth = 0
        var i = open
        while (i < message.length) {
            when (message[i]) {
                QUOTE -> {
                    i = skipQuote(message, i)
                    continue
                }

                '{' -> {
                    depth++
                }

                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }
}

/** The outcome of [IcuArguments.parse]. */
sealed interface IcuResult {
    /** The message is well formed; [arguments] are its top-level argument names in order. */
    data class Parsed(
        val arguments: Set<String>,
    ) : IcuResult

    /** The braces do not balance, or an argument has no valid name; [index] is where parsing stopped. */
    data class Malformed(
        val index: Int,
    ) : IcuResult
}
