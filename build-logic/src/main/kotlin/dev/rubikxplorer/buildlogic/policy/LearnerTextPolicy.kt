package dev.rubikxplorer.buildlogic.policy

/**
 * AD-12 / NFR-10: no Learner-facing string literal in `:core:*` or `:app` main sources; Learner text
 * lives in content data (`content/strings/en.json`) or Android resources.
 *
 * After comments are stripped, a string literal is a violation when it
 * - holds a letter and is the first argument of `Text(` (unless it is a content key such as
 *   `msg.validation.parity`), of `BasicText(` or `AnnotatedString(`, or the value of a `text`, `contentDescription`, `label`, `placeholder`,
 *   `title`, `stateDescription` or `onClickLabel` named argument; or
 * - holds two or more whitespace-separated words, unless it sits in a programmer-error context:
 *   `require`, `requireNotNull`, `check`, `checkNotNull`, `error`, `throw`, an `*Exception(` constructor or a
 *   `toString()` override or an `Unavailable` result constructor.
 *
 * Pure: the Gradle task reads the files and calls [check].
 */
object LearnerTextPolicy {
    /** Named arguments whose value a screen shows or speaks to the Learner. */
    val learnerFacingArguments: Set<String> =
        setOf("text", "contentDescription", "label", "placeholder", "title", "stateDescription", "onClickLabel")

    /** Calls whose string arguments explain a programmer error, never shown to the Learner. */
    val programmerErrorCalls: Set<String> = setOf("require", "requireNotNull", "check", "checkNotNull", "error")

    private val CONTENT_KEY = Regex("[a-z][a-z0-9-]*(\\.[a-z0-9-]+)+")
    private val WORD = Regex("\\S*[\\p{L}${KotlinLexer.TEMPLATE}]\\S*")

    /** One violation in [file] at 1-based [line]. */
    data class Violation(
        val file: String,
        val line: Int,
        val literal: String,
        val rule: Rule,
    ) {
        override fun toString(): String = "$file:$line: ${rule.description}: \"$literal\""
    }

    /** Which rule a [Violation] broke. */
    enum class Rule(
        val description: String,
    ) {
        UI_TEXT("Learner-facing literal passed to a text parameter; use a string resource or Text(key)"),
        PROSE("multi-word string literal outside a programmer-error context; move it to content strings or resources"),
    }

    /** Every violation in the Kotlin [source] of [file]; empty when the file passes. */
    fun check(
        file: String,
        source: String,
    ): List<Violation> {
        val code = KotlinLexer.stripComments(source)
        return KotlinLexer.literals(code).mapNotNull { literal ->
            val text = literal.visibleText
            val rule =
                when {
                    !text.any(Char::isLetter) -> null
                    isUiText(code, literal, text) -> Rule.UI_TEXT
                    wordCount(text) >= 2 && !isProgrammerError(code, literal) -> Rule.PROSE
                    else -> null
                } ?: return@mapNotNull null
            Violation(file, KotlinLexer.lineOf(code, literal.start), literal.raw, rule)
        }
    }

    private fun wordCount(text: String): Int = WORD.findAll(text).count()

    /** Composables and builders whose first argument is shown as-is; content keys are no exemption. */
    val displayTextCalls: Set<String> = setOf("BasicText", "AnnotatedString")

    /**
     * First argument of `Text(` (unless a content key, as domain `Text(key)` takes), of a
     * [displayTextCalls] callee, or the value of a Learner-facing named argument.
     */
    private fun isUiText(
        code: String,
        literal: KotlinLexer.Literal,
        text: String,
    ): Boolean {
        val before = code.substring(0, literal.start).trimEnd()
        if (before.endsWith("(")) {
            val callee = KotlinLexer.identifierBefore(before, before.length - 1)
            if (callee == "Text" && !CONTENT_KEY.matches(text)) return true
            if (callee in displayTextCalls) return true
        }
        if (before.endsWith("=") && !before.endsWith("==") && !before.endsWith("!=")) {
            val name = KotlinLexer.identifierBefore(before, before.length - 1)
            if (name in learnerFacingArguments) return true
        }
        return false
    }

    private fun isExceptionConstructor(callee: String): Boolean = callee.endsWith("Exception") && callee.first().isUpperCase()

    private fun isProgrammerError(
        code: String,
        literal: KotlinLexer.Literal,
    ): Boolean {
        val context = KotlinLexer.enclosingCalls(code, literal.start)
        if (context.any {
                it.callee in programmerErrorCalls ||
                    it.callee == "Unavailable" ||
                    isExceptionConstructor(it.callee) ||
                    it.afterThrow
            }
        ) {
            return true
        }
        return KotlinLexer.enclosingFunction(code, literal.start) == "toString"
    }
}

/** Just enough Kotlin lexing for [LearnerTextPolicy]: comments, string literals and call context. */
internal object KotlinLexer {
    /** A string literal: its [raw] body, the [visibleText] with templates removed, and its [start] offset. */
    data class Literal(
        val start: Int,
        val raw: String,
        val visibleText: String,
    )

    /** A call (or brace block) enclosing an offset, innermost first. */
    data class Enclosing(
        val callee: String,
        val afterThrow: Boolean,
    )

    /**
     * Stands in for a template in visible text: a private-use char, so it is never a letter (an
     * all-template literal has no letter) but it still counts as a word ("$count moves" is two).
     */
    const val TEMPLATE = '\uE000'

    private val IDENTIFIER_CHAR: (Char) -> Boolean = { it.isLetterOrDigit() || it == '_' }

    /** [source] with every comment replaced by spaces (newlines kept), so offsets and lines hold. */
    fun stripComments(source: String): String {
        val out = StringBuilder(source)
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("\"\"\"", i) -> {
                    i = rawStringEnd(source, i)
                }

                source[i] == '"' -> {
                    i = stringEnd(source, i)
                }

                source[i] == '\'' -> {
                    i = charEnd(source, i)
                }

                source.startsWith("//", i) -> {
                    while (i < source.length && source[i] != '\n') out[i++] = ' '
                }

                source.startsWith("/*", i) -> {
                    var depth = 0
                    while (i < source.length) {
                        if (source.startsWith("/*", i)) {
                            depth++
                            out[i] = ' '
                            out[i + 1] = ' '
                            i += 2
                        } else if (source.startsWith("*/", i)) {
                            depth--
                            out[i] = ' '
                            out[i + 1] = ' '
                            i += 2
                            if (depth == 0) break
                        } else {
                            if (source[i] != '\n') out[i] = ' '
                            i++
                        }
                    }
                }

                else -> {
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Every top-level string literal in comment-free [code]; literals nested in templates are included. */
    fun literals(code: String): List<Literal> {
        val found = mutableListOf<Literal>()
        var i = 0
        while (i < code.length) {
            when {
                code.startsWith("\"\"\"", i) -> {
                    val end = rawStringEnd(code, i)
                    found += literal(code, i, i + 3, end - 3, found)
                    i = end
                }

                code[i] == '"' -> {
                    val end = stringEnd(code, i)
                    found += literal(code, i, i + 1, end - 1, found)
                    i = end
                }

                code[i] == '\'' -> {
                    i = charEnd(code, i)
                }

                else -> {
                    i++
                }
            }
        }
        return found.sortedBy { it.start }
    }

    /** The literal with body `code[from, to)`; string literals inside `${…}` templates go to [nested]. */
    private fun literal(
        code: String,
        start: Int,
        from: Int,
        to: Int,
        nested: MutableList<Literal>,
    ): Literal {
        val visible = StringBuilder()
        var i = from
        while (i < to) {
            val char = code[i]
            when {
                char == '\\' -> {
                    val escaped = code.getOrNull(i + 1) ?: ' '
                    visible.append(if (escaped in "ntrb") ' ' else escaped)
                    i += 2
                }

                char == '$' && code.getOrNull(i + 1) == '{' -> {
                    val close = templateEnd(code, i + 1)
                    nested += literals(code.substring(i + 2, close)).map { it.copy(start = it.start + i + 2) }
                    visible.append(TEMPLATE)
                    i = close + 1
                }

                char == '$' && code.getOrNull(i + 1)?.let { it.isLetter() || it == '_' } == true -> {
                    i++
                    while (i < to && IDENTIFIER_CHAR(code[i])) i++
                    visible.append(TEMPLATE)
                }

                else -> {
                    visible.append(char)
                    i++
                }
            }
        }
        return Literal(start, code.substring(from, to), visible.toString())
    }

    /** Index just past the closing quote of the `"` string at [open]. */
    private fun stringEnd(
        code: String,
        open: Int,
    ): Int {
        var i = open + 1
        while (i < code.length) {
            when {
                code[i] == '\\' -> i += 2
                code[i] == '$' && code.getOrNull(i + 1) == '{' -> i = templateEnd(code, i + 1) + 1
                code[i] == '"' || code[i] == '\n' -> return i + 1
                else -> i++
            }
        }
        return code.length
    }

    /** Index just past the closing `"""` of the raw string at [open]. */
    private fun rawStringEnd(
        code: String,
        open: Int,
    ): Int {
        var i = open + 3
        while (i < code.length) {
            when {
                code[i] == '$' && code.getOrNull(i + 1) == '{' -> {
                    i = templateEnd(code, i + 1) + 1
                }

                code.startsWith("\"\"\"", i) -> {
                    var end = i + 3
                    while (end < code.length && code[end] == '"') end++
                    return end
                }

                else -> {
                    i++
                }
            }
        }
        return code.length
    }

    /** Index of the `}` closing the template brace at [open], skipping strings inside it. */
    private fun templateEnd(
        code: String,
        open: Int,
    ): Int {
        var depth = 0
        var i = open
        while (i < code.length) {
            when {
                code.startsWith("\"\"\"", i) -> {
                    i = rawStringEnd(code, i)
                    continue
                }

                code[i] == '"' -> {
                    i = stringEnd(code, i)
                    continue
                }

                code[i] == '\'' -> {
                    i = charEnd(code, i)
                    continue
                }

                code[i] == '{' -> {
                    depth++
                }

                code[i] == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return code.length - 1
    }

    /** Index just past a char literal at [open] (`'a'`, `'\n'`, `'A'`); a lone `'` is skipped. */
    private fun charEnd(
        code: String,
        open: Int,
    ): Int {
        val close = code.indexOf('\'', open + if (code.getOrNull(open + 1) == '\\') 3 else 2)
        return if (close in open + 2..open + 8) close + 1 else open + 1
    }

    /** 1-based line of [offset]. */
    fun lineOf(
        code: String,
        offset: Int,
    ): Int = code.substring(0, offset).count { it == '\n' } + 1

    /** The identifier ending just before index [end] of [text] (whitespace skipped), or `""`. */
    fun identifierBefore(
        text: String,
        end: Int,
    ): String {
        var stop = end
        while (stop > 0 && text[stop - 1].isWhitespace()) stop--
        var begin = stop
        while (begin > 0 && IDENTIFIER_CHAR(text[begin - 1])) begin--
        return text.substring(begin, stop)
    }

    /**
     * The calls whose `(` or trailing-lambda `{` encloses [offset], innermost first. A `{` that
     * follows `)` belongs to the call before that `(` (`require(x) { "…" }`); a `{` after an
     * identifier belongs to that identifier (`error { … }`).
     */
    fun enclosingCalls(
        code: String,
        offset: Int,
    ): List<Enclosing> = openBrackets(code, offset).reversed().map { open -> enclosing(code, open) }

    private fun enclosing(
        code: String,
        open: Int,
    ): Enclosing {
        var end = open
        while (end > 0 && code[end - 1].isWhitespace()) end--
        if (code[open] == '{' && end > 0 && code[end - 1] == ')') {
            val paren = matchingOpen(code, end - 1)
            if (paren != null) return enclosing(code, paren)
        }
        val callee = identifierBefore(code, end)
        val beforeCallee = identifierBefore(code, end - callee.length)
        return Enclosing(callee, afterThrow = beforeCallee == "throw" || callee == "throw")
    }

    /** The name of the innermost `fun` whose body (block or expression) holds [offset], or null. */
    fun enclosingFunction(
        code: String,
        offset: Int,
    ): String? {
        val here = openBrackets(code, offset)
        val declarations =
            DECLARATION
                .findAll(code.substring(0, offset))
                .map { it to openBrackets(code, it.range.first) }
                .filter { (_, scope) -> scope.size <= here.size && here.take(scope.size) == scope }
                .toList()
        // A `fun` holds the literal unless a later declaration in the same scope shows it has ended.
        return declarations.indices
            .reversed()
            .firstOrNull { i ->
                val (match, scope) = declarations[i]
                match.groups[FUN_NAME_GROUP] != null && declarations.drop(i + 1).none { it.second == scope }
            }?.let { declarations[it].first.groups[FUN_NAME_GROUP]?.value }
    }

    private const val FUN_NAME_GROUP = 2

    /** A declaration keyword; group 2 is the name when it is a `fun`. */
    private val DECLARATION =
        Regex(
            "\\b(?:(val|var|class|object|interface|init|constructor|typealias)\\b|" +
                "fun\\s+(?:<[^>]*>\\s*)?(?:[\\w.]+\\.)?(\\w+)\\s*\\()",
        )

    /** Offsets of the `(`, `[` and `{` still open at [offset], outermost first; strings and chars are skipped. */
    private fun openBrackets(
        code: String,
        offset: Int,
    ): List<Int> {
        val stack = ArrayDeque<Int>()
        var i = 0
        while (i < offset) {
            when {
                code.startsWith("\"\"\"", i) -> {
                    val end = rawStringEnd(code, i)
                    if (end > offset) return stack.toList()
                    i = end
                    continue
                }

                code[i] == '"' -> {
                    val end = stringEnd(code, i)
                    if (end > offset) return stack.toList()
                    i = end
                    continue
                }

                code[i] == '\'' -> {
                    i = charEnd(code, i)
                    continue
                }

                code[i] in "([{" -> {
                    stack.addLast(i)
                }

                code[i] in ")]}" -> {
                    stack.removeLastOrNull()
                }
            }
            i++
        }
        return stack.toList()
    }

    private fun matchingOpen(
        code: String,
        close: Int,
    ): Int? = openBrackets(code, close).lastOrNull()
}
