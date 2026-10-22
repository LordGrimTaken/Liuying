package com.liuying.app.ui.settings

import com.liuying.app.data.repo.InstalledPlugin
import com.liuying.app.domain.plugins.PluginInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouLogicTest {
    private fun plugin(id: String, name: String = id, desc: String = "") =
        InstalledPlugin(1, PluginInfo(id, name, "1.0", desc, "https://x/$id.js", emptyList(), emptyList()), false)

    @Test fun summaries() {
        assertEquals("None added", YouLogic.sourcesSummary(0))
        assertEquals("3 added", YouLogic.sourcesSummary(3))
        assertEquals("3 added, 2 on", YouLogic.sourcesSummary(3, 2))
        assertEquals("No catalogs yet", YouLogic.layoutSummary(0, 0))
        assertEquals("1 catalog", YouLogic.layoutSummary(1, 0))
        assertEquals("46 catalogs, 2 hidden", YouLogic.layoutSummary(46, 2))
        assertEquals("None added", YouLogic.reposSummary(0, 0, 0))
        assertEquals("1 repo, 66 plugins, 8 enabled", YouLogic.reposSummary(1, 66, 8))
        assertEquals("Auto-select, 10 s seek", YouLogic.playbackSummary("Auto-select", 10))
    }

    @Test fun groupingKeepsOrder() {
        val g = YouLogic.groupBySource(listOf("a1" to "A", "b1" to "B", "a2" to "A")) { it.second }
        assertEquals(listOf("A", "B"), g.map { it.source })
        assertEquals(listOf("a1", "a2"), g[0].rows.map { it.first })
    }

    @Test fun moveWithinGroup() {
        val all = listOf("a1", "b1", "a2", "b2")
        val a = listOf("a1", "a2")
        assertEquals(2, YouLogic.moveDeltaWithinGroup(all, a, "a1", 1))
        assertEquals(-2, YouLogic.moveDeltaWithinGroup(all, a, "a2", -1))
        assertNull(YouLogic.moveDeltaWithinGroup(all, a, "a1", -1))
        assertNull(YouLogic.moveDeltaWithinGroup(all, a, "a2", 1))
    }

    @Test fun filterPlugins() {
        val ps = listOf(plugin("one", "Alpha Scraper"), plugin("two", "Beta", "finds ALPHA things"), plugin("three", "Gamma"))
        assertEquals(3, YouLogic.filterPlugins(ps, "  ").size)
        assertEquals(listOf("one", "two"), YouLogic.filterPlugins(ps, "alpha").map { it.info.id })
        assertEquals(listOf("three"), YouLogic.filterPlugins(ps, "THREE").map { it.info.id })
        assertEquals(0, YouLogic.filterPlugins(ps, "zzz").size)
    }

    @Test fun openStateRoundTrip() {
        assertEquals(setOf("account"), YouLogic.decodeOpen(null))
        assertEquals(emptySet<String>(), YouLogic.decodeOpen(""))
        val s = setOf("layout", "repos")
        assertEquals(s, YouLogic.decodeOpen(YouLogic.encodeOpen(s)))
        assertEquals(setOf("repos"), YouLogic.decodeOpen("bogus,repos"))
    }
}
