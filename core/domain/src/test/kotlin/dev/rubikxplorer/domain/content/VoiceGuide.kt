package dev.rubikxplorer.domain.content

import dev.rubikxplorer.domain.cube.Notation
import dev.rubikxplorer.domain.cube.NotationResult

/**
 * The mechanical part of the explanation voice (UX-DR24, EXPERIENCE.md, PRD addendum §I), checked on
 * one English message: no `!`, no judging or stiff words, no first person, short sentences that end
 * with a period, and Notation alongside words, never instead of them. Only these rules are checked;
 * slot meaning (what Goal, Keep, Watch and Notice each say), second person and Glossary use are
 * reviewed by hand.
 */
internal object VoiceGuide {
    /** The most words one sentence may hold. */
    const val MAX_SENTENCE_WORDS = 20

    /** The fewest plain words (not Notation) a message needs, so Notation never stands alone. */
    const val MIN_PLAIN_WORDS = 2

    private val BANNED = Regex("\\b(wrong\\w*|errors?|\\w*correct\\w*|perform\\w*|let[’']?s|let\\s+us)\\b", RegexOption.IGNORE_CASE)
    private val FIRST_PERSON = Regex("\\b(we|us|our|ours|I|me|my)\\b", RegexOption.IGNORE_CASE)
    private val SENTENCE_END = Regex("(?<=[.?])\\s+")
    private val TRIM = Regex("^[\"(]+|[.,:;\")]+$")

    /**
     * Why [message] breaks the voice, empty when it follows it. A [title] is a short label, not a
     * sentence, so it skips the sentence-shape rules.
     */
    fun problems(
        message: String,
        title: Boolean = false,
    ): List<String> {
        val problems = mutableListOf<String>()
        if ('!' in message) problems += "has an exclamation mark"
        BANNED.findAll(message).forEach { problems += "uses \"${it.value}\"" }
        FIRST_PERSON.findAll(message).forEach { problems += "speaks in the first person (\"${it.value}\")" }
        if (title) return problems

        if (!message.endsWith('.')) problems += "does not end as a declarative sentence"
        message.split(SENTENCE_END).forEach { sentence ->
            val words = sentence.split(' ').filter { it.isNotBlank() }.size
            if (words > MAX_SENTENCE_WORDS) problems += "has a sentence of $words words: \"$sentence\""
        }
        val plain = message.split(Regex("\\s+")).map { it.replace(TRIM, "") }.filter { it.isNotEmpty() && !isNotation(it) }
        if (plain.size < MIN_PLAIN_WORDS) problems += "shows Notation without words"
        return problems
    }

    private fun isNotation(token: String): Boolean = (Notation.parse(token) as? NotationResult.Parsed)?.moves?.isNotEmpty() == true
}
