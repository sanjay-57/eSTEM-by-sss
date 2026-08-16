package com.sss.estem.separation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The address is typed by hand on a phone keyboard, so the forms people actually type have to
 * work — and the ones that cannot work have to be rejected here rather than surface later as a
 * connection error that blames the network.
 */
class ServerSettingsTest {

    @Test
    fun `accepts what people type`() {
        val expected = "http://192.168.1.20:8765"
        for (typed in listOf(
            "http://192.168.1.20:8765",
            "http://192.168.1.20:8765/",
            "  http://192.168.1.20:8765  ",
            "192.168.1.20:8765",
        )) {
            assertEquals(typed, expected, ServerSettings.normalise(typed))
        }
    }

    @Test
    fun `keeps a scheme that was given`() {
        assertEquals("https://stems.example.com", ServerSettings.normalise("https://stems.example.com/"))
    }

    @Test
    fun `assumes http for a bare host`() {
        assertEquals("http://laptop.local", ServerSettings.normalise("laptop.local"))
    }

    @Test
    fun `rejects anything without a host`() {
        for (typed in listOf("", "   ", "http://", "https:///stems", "http://:8765")) {
            assertNull(typed, ServerSettings.normalise(typed))
        }
    }
}
