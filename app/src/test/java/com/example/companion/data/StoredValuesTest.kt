package com.example.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Stage 29: what a saved value must look like before it is used. Plain JVM. WRITTEN BUT NOT RUN (no Gradle / Android SDK was available). */
class StoredValuesTest {

    @Test fun roleAcceptsOnlyTheExactEnumNames() {
        assertEquals(Role.SIM_PHONE, StoredValues.role("SIM_PHONE"))
        assertEquals(Role.MAIN_PHONE, StoredValues.role("MAIN_PHONE"))
        listOf(null, "", " ", "sim_phone", "SIM_PHONE ", "Main Phone", "ADMIN", "0", "SIM_PHONE\u0000").forEach { assertNull(it, StoredValues.role(it)) }
    }

    @Test fun deviceAddressMustBeSixHexPairs() {
        assertEquals("AA:BB:CC:DD:EE:FF", StoredValues.deviceAddress("AA:BB:CC:DD:EE:FF"))
        assertEquals("aa:bb:cc:dd:ee:0f", StoredValues.deviceAddress("aa:bb:cc:dd:ee:0f"))   // case is kept as stored
        listOf(null, "", "AA:BB:CC:DD:EE", "AA:BB:CC:DD:EE:FF:00", "AA-BB-CC-DD-EE-FF", "GG:BB:CC:DD:EE:FF", " AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF\n", "Pixel 7")
            .forEach { assertNull(it, StoredValues.deviceAddress(it)) }
    }

    @Test fun deviceNameDropsBlankAndControlCharactersAndIsCapped() {
        assertEquals("Pixel 7", StoredValues.deviceName("Pixel 7"))
        assertEquals("نام گوشی", StoredValues.deviceName("نام گوشی"))
        assertNull(StoredValues.deviceName(null))
        assertNull(StoredValues.deviceName(""))
        assertNull(StoredValues.deviceName("   "))
        assertNull(StoredValues.deviceName("\u0000\n\t"))
        assertEquals("AB", StoredValues.deviceName("A\u0000B"))
        assertEquals(StoredValues.MAX_NAME, StoredValues.deviceName("x".repeat(1000))!!.length)
    }

    @Test fun negativeTimeMeansNever() {
        assertEquals(0L, StoredValues.timestamp(-1L))
        assertEquals(0L, StoredValues.timestamp(Long.MIN_VALUE))
        assertEquals(0L, StoredValues.timestamp(0L))
        assertEquals(1_700_000_000_000L, StoredValues.timestamp(1_700_000_000_000L))
    }
}
