package com.example.companion.i18n

import android.app.Activity
import android.content.Context
import android.view.View

/**
 * Stage 16: the one base class of every screen. It makes each screen follow the language chosen inside the app (not the phone's
 * system language) for BOTH text resources and layout direction.
 *
 * - attachBaseContext wraps the context with [LocalizedContext], so the ActionBar, dialogs, toasts and system widgets resolve
 *   their layout direction from the app language. Persian -> RTL, English -> LTR (English returns the base context unchanged).
 * - The content root is also given an explicit direction ([applyAppLayoutDirection]) so a screen never depends on the phone's
 *   system language: a phone set to Persian with the app set to English stays left-to-right, and the reverse.
 * - [languageAtCreate] is what this instance was built with. When the user comes back (onStart) and the app language is different
 *   (changed on another screen), the screen recreates itself, so a change applies everywhere without a restart or reinstall.
 *
 * No state, no Bluetooth, SMS, contacts or call code: only language and direction.
 */
open class LocalizedActivity : Activity() {
    /** The language this instance was created with. */
    protected var languageAtCreate: AppLanguage = AppLocale.current
        private set
    private var recreating = false

    override fun attachBaseContext(newBase: Context) {
        languageAtCreate = AppLocale.current
        super.attachBaseContext(LocalizedContext.wrap(newBase, languageAtCreate))
    }

    override fun setContentView(view: View) {
        view.applyAppLayoutDirection()
        super.setContentView(view)
    }

    override fun onStart() {
        super.onStart()
        // Language changed while this screen was in the back stack: redraw it in the new language and direction.
        if (languageAtCreate != AppLocale.current) recreate()
    }

    /** Several screens already ask for a recreate when the language changed; two requests in one pass must become one. */
    override fun recreate() {
        if (recreating) return
        recreating = true
        super.recreate()
    }
}
