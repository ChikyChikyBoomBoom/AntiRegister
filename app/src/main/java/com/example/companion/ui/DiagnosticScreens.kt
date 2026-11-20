package com.example.companion.ui

/**
 * Stage 3: visibility switch for the three developer screens [AudioDiagActivity], [AudioTransportTestActivity] and [HfpDiagActivity].
 * Every implementation stays in the project; while this is false no menu or button leads to them, and each screen finishes itself
 * immediately if something still manages to launch it (before any listener, probe or audio object is touched).
 *
 * Plain `const val`: re-enabling is a deliberate source change (see STAGE3_NOTES.md), never a setting or an intent extra.
 * Pure Kotlin (no Android classes). This flag does not enable the audio / HFP features themselves; those have their own switches
 * in [com.example.companion.audio.AudioTransferFeatures].
 */
object DiagnosticScreens {
    const val VISIBLE = false

    /** Function form so call sites do not become "constant condition" compiler warnings. */
    fun isVisible(): Boolean = VISIBLE
}
