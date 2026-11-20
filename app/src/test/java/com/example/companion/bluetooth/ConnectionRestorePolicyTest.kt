package com.example.companion.bluetooth

import com.example.companion.bluetooth.ConnectionManager.State
import com.example.companion.data.Role
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 30 JVM tests of the restart / foreground connection rule. WRITTEN BUT NOT RUN. */
class ConnectionRestorePolicyTest {

    private fun start(
        role: Role? = Role.MAIN_PHONE, device: Boolean = true, state: State = State.DISCONNECTED,
        userStopped: Boolean = false, resume: Boolean = false, perms: Boolean = true, bt: Boolean = true,
        auto: Boolean = true, intent: Boolean = false
    ) = ConnectionRestorePolicy.shouldStart(role, device, state, userStopped, resume, perms, bt, auto, intent)

    @Test fun mainPhone_savedDevice_autoOn_starts() = assertTrue(start())

    @Test fun simPhone_needsNoDevice_andIgnoresAutoReconnect() {
        assertTrue(start(role = Role.SIM_PHONE, device = false, auto = false))
    }

    @Test fun noRole_neverStarts() = assertFalse(start(role = null))

    @Test fun mainPhone_withoutSavedDevice_neverStarts() = assertFalse(start(device = false))

    @Test fun onlyFromCleanIdle() {
        for (s in State.values()) if (s != State.DISCONNECTED) assertFalse("state $s", start(state = s))
    }

    @Test fun explicitDisconnect_survivesRestart() = assertFalse(start(userStopped = true, intent = true))

    @Test fun connectionManagerResumingByItself_isLeftAlone() = assertFalse(start(resume = true))

    @Test fun missingPermissionOrBluetoothOff_doesNotStart() {
        assertFalse(start(perms = false)); assertFalse(start(bt = false))
    }

    @Test fun mainPhone_autoReconnectOff_doesNotStartByItself_butANotificationTapDoes() {
        assertFalse(start(auto = false))
        assertTrue(start(auto = false, intent = true))
    }

    @Test fun notificationTap_stillRespectsEverythingElse() {
        assertFalse(start(auto = false, intent = true, perms = false))
        assertFalse(start(auto = false, intent = true, state = State.CONNECTED))
    }
}
