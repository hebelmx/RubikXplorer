package dev.cubentia.app.content

import android.icu.text.MessageFormat
import android.icu.util.ULocale
import dev.cubentia.domain.content.TextKey
import dev.cubentia.domain.text.Text

/**
 * Resolves content text (`Text(key, args)`, AD-12) against a bundle's [strings] (`key → ICU message`)
 * with ICU `MessageFormat` named arguments. The content test guarantees every key exists; a missing
 * one resolves to the key itself, so it shows up in review instead of crashing.
 */
class ContentStrings(
    private val strings: Map<String, String>,
    private val locale: ULocale = ULocale.ENGLISH,
) {
    fun resolve(text: Text): String {
        val pattern = strings[text.key] ?: return text.key
        return MessageFormat(pattern, locale).format(text.args)
    }

    fun resolve(key: TextKey): String = resolve(Text(key.value))
}
