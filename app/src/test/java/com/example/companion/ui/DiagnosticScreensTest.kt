package com.example.companion.ui

import org.junit.Assert.assertFalse
import org.junit.Test

/** Stage 3: the developer screens ship hidden. WRITTEN BUT NOT EXECUTED (no Kotlin compiler / Gradle available). */
class DiagnosticScreensTest {
    @Test fun diagnosticScreensShipHidden() {
        assertFalse(DiagnosticScreens.VISIBLE)
        assertFalse(DiagnosticScreens.isVisible())
    }
}
