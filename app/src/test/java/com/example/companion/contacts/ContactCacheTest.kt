package com.example.companion.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the in-memory lookup memo. WRITTEN BUT NOT RUN. */
class ContactCacheTest {
    private var clock = 0L
    private fun cache(max: Int = 3, ttl: Long = 1_000) = ContactCache(max, ttl) { clock }

    @Test fun neverAskedIsNull() { assertNull(cache().get("+15551234")) }

    @Test fun remembersAMatch() {
        val c = cache(); c.put("+15551234", ContactMatch("Ann", "content://photo/1"))
        assertEquals("Ann", c.get("+15551234")!!.match!!.name)
    }

    @Test fun remembersNotFoundSoItIsNotAskedAgain() {
        val c = cache(); c.put("+15559999", null)
        val hit = c.get("+15559999")
        assertNotNull(hit); assertNull(hit!!.match)
    }

    @Test fun answersExpireAfterTheTimeLimit() {
        val c = cache(); c.put("a", ContactMatch("Ann", null))
        clock = 1_000; assertNotNull(c.get("a"))
        clock = 1_001; assertNull(c.get("a"))
        assertEquals(0, c.size())
    }

    @Test fun oldestEntryIsDroppedBeyondTheLimit() {
        val c = cache(max = 2)
        c.put("a", null); c.put("b", null); c.get("a")      // "b" is now the least recently used
        c.put("c", null)
        assertNotNull(c.get("a")); assertNull(c.get("b")); assertNotNull(c.get("c"))
        assertTrue(c.size() <= 2)
    }

    @Test fun clearForgetsEverything() {
        val c = cache(); c.put("a", ContactMatch("Ann", null)); c.clear()
        assertNull(c.get("a"))
    }
}
