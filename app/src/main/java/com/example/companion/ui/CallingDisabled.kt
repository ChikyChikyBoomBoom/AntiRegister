package com.example.companion.ui

import android.content.Context
import android.widget.Toast
import com.example.companion.calls.CallFeatures

/** Stage 7: what a user-facing calling action does while calling is off (see [CallFeatures]). It starts nothing. */
fun Context.showCallingDisabled() {
    // Stage 19: the constant is Persian-only (kept, tests pin it), so the toast now comes from the catalog in the selected language.
    Toast.makeText(this, com.example.companion.i18n.tr(com.example.companion.i18n.StringKey.HOME_CALLING_DISABLED), Toast.LENGTH_LONG).show()
}

/**
 * Stage 13: the same "calling is off" toast in the selected language, for the Home and Messages screens. [showCallingDisabled] above
 * is unchanged and still used by the other screens. It starts nothing either.
 */
fun Context.showCallingDisabledLocalized() {
    Toast.makeText(this, com.example.companion.i18n.tr(com.example.companion.i18n.StringKey.HOME_CALLING_DISABLED), Toast.LENGTH_LONG).show()
}
