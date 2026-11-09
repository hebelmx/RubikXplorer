package dev.cubentia.domain.content

import java.io.File

/** A fixture bundle under `content-fixtures/`: [variant]'s files, falling back to `valid/` for the rest. */
internal fun fixture(variant: String): ContentSource {
    val overlay = ContentSource.classpath("content-fixtures/$variant")
    val valid = ContentSource.classpath("content-fixtures/valid")
    return ContentSource { path -> overlay.read(path) ?: valid.read(path) }
}

/** The text of classpath resource [path]. */
internal fun resourceText(path: String): String =
    checkNotNull(ContentChecks::class.java.classLoader.getResource(path)) { "missing resource $path" }.readText()

/** Content checks that need raw text, which kotlinx.serialization cannot give (it keeps the last duplicate). */
internal object ContentChecks {
    /** Keys that appear more than once in the flat JSON object [text], in order of their second appearance. */
    fun duplicateKeys(text: String): List<String> {
        val seen = mutableSetOf<String>()
        val duplicates = mutableListOf<String>()
        var depth = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '{', '[' -> {
                    depth++
                }

                '}', ']' -> {
                    depth--
                }

                '"' -> {
                    val end = stringEnd(text, i)
                    val isKey = text.substring(end + 1).trimStart().startsWith(":")
                    if (depth == 1 && isKey) {
                        val key = text.substring(i + 1, end)
                        if (!seen.add(key)) duplicates += key
                    }
                    i = end
                }
            }
            i++
        }
        return duplicates
    }

    private fun stringEnd(
        text: String,
        open: Int,
    ): Int {
        var i = open + 1
        while (text[i] != '"') i += if (text[i] == '\\') 2 else 1
        return i
    }

    /** One `Text("key", …)` call found in source. */
    data class TextCall(
        val file: String,
        val line: Int,
        val key: String,
        val args: Set<String>,
    )

    /**
     * Every `Text("key")` / `Text("key", mapOf("name" to …))` call in [source], comments excluded.
     * Calls whose key is not a literal, or whose args are not a `mapOf` literal, are skipped.
     */
    fun textCalls(
        file: String,
        source: String,
    ): List<TextCall> {
        val code = stripComments(source)
        return CALL
            .findAll(code)
            .mapNotNull { match ->
                val open = match.range.last
                val close = closing(code, open) ?: return@mapNotNull null
                val args = splitTopLevel(code.substring(open + 1, close))
                val key = LITERAL.matchEntire(args.first())?.groupValues?.get(1) ?: return@mapNotNull null
                val names =
                    when (val second = args.getOrNull(1)) {
                        null -> emptySet()
                        else -> if (second.startsWith("mapOf(")) ARG_NAME.findAll(second).map { it.groupValues[1] }.toSet() else null
                    } ?: return@mapNotNull null
                TextCall(file, code.substring(0, open).count { it == '\n' } + 1, key, names)
            }.toList()
    }

    /** One finding per call whose key is missing from [strings] or whose args differ from its message's. */
    fun textCallProblems(
        calls: List<TextCall>,
        strings: Map<String, String>,
    ): List<String> =
        calls.mapNotNull { call ->
            val message = strings[call.key] ?: return@mapNotNull "${call.file}:${call.line} ${call.key} is missing from en.json"
            when (val parsed = IcuArguments.parse(message)) {
                is IcuResult.Malformed -> {
                    "${call.file}:${call.line} ${call.key} has a malformed message"
                }

                is IcuResult.Parsed -> {
                    if (parsed.arguments == call.args) {
                        null
                    } else {
                        "${call.file}:${call.line} ${call.key} passes ${call.args.sorted()} but its message takes " +
                            "${parsed.arguments.sorted()}"
                    }
                }
            }
        }

    /** Every `Text(...)` call under the Kotlin source directory [root]. */
    fun textCallsUnder(root: File): List<TextCall> =
        root
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .flatMap { textCalls(it.relativeTo(root).path, it.readText()) }
            .toList()

    private val CALL = Regex("(?<![A-Za-z0-9_.])(?:Content)?Text\\(")
    private val LITERAL = Regex("\"([^\"\\\\$]*)\"")
    private val ARG_NAME = Regex("\"([^\"]+)\"\\s+to\\b")

    private fun closing(
        code: String,
        open: Int,
    ): Int? {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '"' -> {
                    i = stringEnd(code, i)
                }

                '(' -> {
                    depth++
                }

                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    private fun splitTopLevel(args: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < args.length) {
            when (args[i]) {
                '"' -> i = stringEnd(args, i)
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) parts += args.substring(start, i).also { start = i + 1 }
            }
            i++
        }
        parts += args.substring(start)
        return parts.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
    }

    /** [source] with comments blanked, newlines kept so line numbers hold. */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val rest = source.startsWith("//", i) to source.startsWith("/*", i)
            when {
                source[i] == '"' -> {
                    val end = stringEnd(source, i)
                    out.append(source, i, end + 1)
                    i = end + 1
                }

                rest.first -> {
                    while (i < source.length && source[i] != '\n') i++
                }

                rest.second -> {
                    val end = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                    source.substring(i, end).forEach { if (it == '\n') out.append('\n') }
                    i = end
                }

                else -> {
                    out.append(source[i])
                    i++
                }
            }
        }
        return out.toString()
    }
}
