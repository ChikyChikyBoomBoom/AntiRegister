package com.example.companion.i18n

import com.example.companion.data.Role

/**
 * Stage 15: the role's name in [language]. [Role.label] is not touched: it stays the English name and is still what the screens that
 * are not translated yet (diagnostics, Status, logs) show. English output of this function equals [Role.label].
 */
fun Role.localizedLabel(language: AppLanguage): String = Strings.text(
    when (this) {
        Role.SIM_PHONE -> StringKey.ROLE_SIM_PHONE
        Role.MAIN_PHONE -> StringKey.ROLE_MAIN_PHONE
    }, language
)
