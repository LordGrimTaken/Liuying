package com.liuying.app.domain.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginFailuresTest {
    @Test fun sameHostFailuresShareOneLineWithAllNames() {
        val g = PluginFailures.group(listOf(
            "HiAnime" to "Could not reach raw.githubusercontent.com (DNS lookup failed)",
            "VidFast" to "Unable to resolve host \"raw.githubusercontent.com\": No address associated with hostname",
            "VidSrc" to "Could not reach raw.githubusercontent.com (DNS lookup failed: x)",
            "VidLink" to "The plugin returned something that is not JSON.",
        ))
        assertEquals(2, g.size)
        assertEquals("3 plugins could not reach raw.githubusercontent.com", g[0].headline)
        assertEquals(listOf("HiAnime", "VidFast", "VidSrc"), g[0].names)
        assertEquals("Plugin VidLink failed: The plugin returned something that is not JSON.", g[1].headline)
    }

    @Test fun identicalOtherMessagesGroupToo() {
        val g = PluginFailures.group(listOf("A" to "Script error: x", "B" to "Script error: x"))
        assertEquals("2 plugins failed: Script error: x", g.single().headline)
    }

    @Test fun dnsHostIsNullForOtherText() { assertNull(PluginFailures.dnsHost("HTTP 500")) }
}
