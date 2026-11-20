package com.example.companion.audio.call

import com.example.companion.app.CompanionApp
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Stage 34B: the application-level switch ships OFF. `DOWNLINK_BRIDGE` is a `const val`, so this test only needs the constant
 * (inlined at compile time); CompanionApp (an Android Application) is never instantiated or loaded.
 * WRITTEN BUT NOT EXECUTED in this environment (it needs the Android compile classpath that Gradle provides).
 */
class CompanionAppSwitchTest {
    @Test fun downlinkBridgeDefaultsToFalse() {
        assertFalse(CompanionApp.DOWNLINK_BRIDGE)
    }

    /** Stage 40: the uplink experiment switch (`uplinkBridge`) ships OFF; also a `const val`, so CompanionApp is never loaded. */
    @Test fun uplinkBridgeDefaultsToFalse() {
        assertFalse(CompanionApp.UPLINK_BRIDGE)
    }
}
