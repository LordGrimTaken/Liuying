package com.liuying.app.domain.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManifestParserTest {
    private val url = "https://repo.example.com/plugins/manifest.json"

    @Test fun parsesRepoAndPlugins() {
        val m = PluginManifestParser.parse(url, """{"id":"r1","name":"Repo","version":"1.2","author":"me","plugins":[
            {"id":"a","name":"Alpha","scriptUrl":"a.js","types":["movie"],"permissions":["network:x.com","network:x.com","storage"]},
            {"id":"b","scriptUrl":"https://cdn.example.com/b.js"}]}""").getOrThrow()
        assertEquals("Repo", m.name); assertEquals("me", m.author); assertEquals(2, m.plugins.size)
        assertEquals("https://repo.example.com/plugins/a.js", m.plugins[0].scriptUrl)
        assertEquals(listOf("network:x.com", "storage"), m.plugins[0].permissions)
        assertEquals("b", m.plugins[1].name)
    }

    @Test fun skipsBadPluginsAndDuplicates() {
        val m = PluginManifestParser.parse(url, """{"id":"r","name":"R","plugins":[
            {"id":"a","scriptUrl":"javascript:alert(1)"},{"name":"noid","scriptUrl":"x.js"},"str",
            {"id":"ok","scriptUrl":"ok.js"},{"id":"ok","scriptUrl":"other.js"},{"id":"file","scriptUrl":"file:///etc/passwd"}]}""").getOrThrow()
        assertEquals(listOf("ok"), m.plugins.map { it.id })
        assertTrue(m.plugins[0].scriptUrl.endsWith("/ok.js"))
    }

    @Test fun rejectsInvalid() {
        assertTrue(PluginManifestParser.parse(url, "not json").isFailure)
        assertTrue(PluginManifestParser.parse(url, "[]").isFailure)
        assertTrue(PluginManifestParser.parse(url, """{"name":"x"}""").isFailure)
    }

    @Test fun noopRunnerIsNotRunnable() {
        assertFalse(NoopPluginRunner.runnable)
        assertTrue(PluginPermissions.describe("network:a.com").contains("a.com"))
        assertTrue(PluginPermissions.describe("weird").contains("Unknown"))
    }
}
