package com.example.companion.i18n

/**
 * Stage 19: shows the connection errors that [com.example.companion.bluetooth.ConnectionManager] words in English in the selected
 * language. DISPLAY ONLY: ConnectionManager (its messages, state machine and retry logic) is not touched; this maps the exact English
 * sentence it produced to a catalog key.
 *
 * Pure Kotlin. English output always equals the input. A text that is not recognised is returned unchanged (never lost, never
 * guessed), and a language with no text for the key falls back to English through [Strings].
 */
object ConnectionErrorText {

    private val exact: Map<String, StringKey> = listOf(
        StringKey.ERR_ONLY_MAIN_SCANS, StringKey.ERR_BT_PERMISSION, StringKey.ERR_BT_OFF, StringKey.ERR_DISCOVERY,
        StringKey.ERR_NO_ROLE, StringKey.ERR_BT_UNSUPPORTED, StringKey.ERR_NO_SIM_SELECTED, StringKey.ERR_BAD_ADDRESS,
        StringKey.ERR_PAIRING_START, StringKey.ERR_NOT_PAIRED, StringKey.ERR_PAIRING_TIMEOUT, StringKey.ERR_BT_WAS_OFF,
        StringKey.ERR_PAIRING_FAILED, StringKey.ERR_TIMEOUT, StringKey.ERR_SIM_UNAVAILABLE, StringKey.ERR_LOST_REMOTE,
        StringKey.ERR_LOST_TIMEOUT, StringKey.ERR_LOST_LINK, StringKey.ERR_LINK_UNSTABLE   // Stage 27: + ERR_LINK_UNSTABLE
    ).associateBy { it.english }

    /** English prefix (everything before the "%1$s") -> key; the rest of the text is the system's own detail and is kept as it is. */
    private val withDetail: List<Pair<String, StringKey>> = listOf(
        StringKey.ERR_UNEXPECTED, StringKey.ERR_LISTEN_FAILED, StringKey.ERR_LISTEN_STOPPED, StringKey.ERR_SOCKET
    ).map { it.english.substringBefore("%1\$s") to it }

    fun localize(text: String, language: AppLanguage): String {
        if (language == AppLanguage.ENGLISH) return text
        exact[text]?.let { return Strings.text(it, language) }
        for ((prefix, key) in withDetail) {
            if (text.startsWith(prefix)) return Strings.text(key, language, text.substring(prefix.length))
        }
        return text
    }

    /** Same for a nullable text (e.g. ConnectionManager.lastError), in the selected app language. */
    fun localizeOrNull(text: String?, language: AppLanguage = AppLocale.current): String? = text?.let { localize(it, language) }
}
