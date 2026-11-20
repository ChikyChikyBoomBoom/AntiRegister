package com.example.companion.ui

import com.example.companion.data.Role
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stage 29: where the app opens for every combination of saved role / setup state, including the ones damaged data produces
 * (a role that could not be read becomes null). Plain JVM. WRITTEN BUT NOT RUN (no Gradle / Android SDK was available).
 */
class SettingsActionsAndOnboardingStateTest {

    @Test fun noRoleAlwaysOpensTheWelcomeScreenWhateverSetupSays() {
        assertEquals(RoleSelectActivity::class.java, startActivityFor(null, setupCompleted = false))
        assertEquals(RoleSelectActivity::class.java, startActivityFor(null, setupCompleted = true))     // "setup finished" with no role: damaged data
        assertEquals(RoleSelectActivity::class.java, homeActivityFor(null))
    }

    @Test fun roleWithoutFinishedSetupOpensSetupForBothRoles() {
        assertEquals(SetupActivity::class.java, startActivityFor(Role.MAIN_PHONE, setupCompleted = false))
        assertEquals(SetupActivity::class.java, startActivityFor(Role.SIM_PHONE, setupCompleted = false))
    }

    @Test fun finishedSetupOpensTheRolesHome() {
        assertEquals(HomeActivity::class.java, startActivityFor(Role.MAIN_PHONE, setupCompleted = true))
        assertEquals(StatusActivity::class.java, startActivityFor(Role.SIM_PHONE, setupCompleted = true))
    }

    @Test fun settingsStateToleratesMissingAndBlankValues() {
        // A role that could not be read, no device, blank name, no error text: every line is still produced.
        val ui = SettingsState.from(null, com.example.companion.bluetooth.ConnectionManager.State.ERROR, null, "  ", "", 0, null)
        assertEquals("Not selected", ui.roleLine)
        assertEquals("-", ui.deviceLine)
        assertEquals("", ui.detail)
    }
}
