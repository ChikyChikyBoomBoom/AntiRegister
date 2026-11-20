package com.example.companion.i18n

import java.util.Locale

/**
 * Stage 12: the UI languages the app can show. Pure Kotlin (no Android types), so it can be unit-tested on a plain JVM.
 *
 * ENGLISH is the default and the fallback for every missing Persian text. [nativeName] is always written in the language itself
 * (never translated), so a user who cannot read the current language can still find their own.
 */
enum class AppLanguage(val code: String, val nativeName: String, val isRtl: Boolean, val localeTag: String) {
    ENGLISH("en", "English", false, "en"),
    PERSIAN("fa", "فارسی", true, "fa");

    fun toLocale(): Locale = Locale.forLanguageTag(localeTag)

    companion object {
        val DEFAULT = ENGLISH

        /** Unknown, empty or null (nothing saved yet) -> English. Never throws. */
        fun fromCode(code: String?): AppLanguage = values().firstOrNull { it.code == code } ?: DEFAULT
    }
}
