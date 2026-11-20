package com.example.companion.i18n

/**
 * Stage 12: digit and direction helpers for Persian. Pure functions. NOT applied to any screen yet.
 *
 * Phone numbers, times and counts are data, not wording: callers decide per field whether to localize them. Phone numbers in
 * particular should normally stay as typed (Latin digits), so nothing here is applied automatically.
 */
object TextDirection {
    private const val PERSIAN_ZERO = '۰'

    /** Replaces 0-9 with the Persian digits ۰-۹ when [language] is Persian; otherwise returns [text] unchanged. */
    fun localizeDigits(text: String, language: AppLanguage): String {
        if (language != AppLanguage.PERSIAN) return text
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(if (c in '0'..'9') PERSIAN_ZERO + (c - '0') else c)
        return sb.toString()
    }

    /** Stage 13: [n] as text, in Persian digits when [language] is Persian. For counts and attempt numbers (not phone numbers). */
    fun number(n: Int, language: AppLanguage): String = localizeDigits(n.toString(), language)

    /**
     * Stage 13: keeps a name or phone number from being re-ordered by the sentence around it. Persian only (English text is returned
     * unchanged). Uses a first-strong isolate, so a Persian name stays right-to-left and a number or Latin name stays left-to-right.
     */
    fun isolate(text: String, language: AppLanguage): String =
        if (language == AppLanguage.PERSIAN && text.isNotEmpty()) "\u2068$text\u2069" else text

    /** Wraps [text] in left-to-right isolate marks, for a number or Latin name that must keep its order inside Persian text. */
    fun isolateLtr(text: String): String = "⁦$text⁩"

    /**
     * Stage 16: like [isolateLtr] but only in Persian. For a number, time or Latin name that sits inside a Persian line and must keep
     * its own order. English text is returned unchanged, so English screens are byte-for-byte what they were.
     */
    fun ltrData(text: String, language: AppLanguage): String =
        if (language == AppLanguage.PERSIAN && text.isNotEmpty()) isolateLtr(text) else text
}
