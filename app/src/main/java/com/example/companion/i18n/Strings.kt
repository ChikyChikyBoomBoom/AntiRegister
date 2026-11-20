package com.example.companion.i18n

import java.util.IllegalFormatException
import java.util.Locale

/**
 * Stage 12: looks a [StringKey] up for a language. Pure functions, no Android types, never throws.
 *
 * Fallback chain: the language's own text -> English text of the key. A bad format string or a wrong number of arguments returns the
 * unformatted text rather than crashing a screen.
 */
object Strings {

    fun text(key: StringKey, language: AppLanguage): String = text(key, language, PersianStrings.table)

    /** Stage 13: same lookup against a given Persian table, so the English fallback can be tested with a table that lacks a key. */
    internal fun text(key: StringKey, language: AppLanguage, persian: Map<StringKey, String>): String = when (language) {
        AppLanguage.PERSIAN -> persian[key] ?: key.english
        AppLanguage.ENGLISH -> key.english
    }

    fun text(key: StringKey, language: AppLanguage, vararg args: Any?): String {
        val pattern = text(key, language)
        if (args.isEmpty()) return pattern
        return try {
            String.format(language.toLocale(), pattern, *args)
        } catch (e: IllegalFormatException) {
            // Persian pattern broken? Try the English one before giving up.
            try { String.format(Locale.ROOT, key.english, *args) } catch (e2: IllegalFormatException) { pattern }
        }
    }

    /** True when [language] has its own text for [key] (English: always). Used to measure translation progress. */
    fun isTranslated(key: StringKey, language: AppLanguage): Boolean = when (language) {
        AppLanguage.ENGLISH -> true
        AppLanguage.PERSIAN -> PersianStrings.table.containsKey(key)
    }
}
