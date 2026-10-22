package com.liuying.app.domain.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSyncLogicTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private val local = SyncedSettings(listOf("l1", "l2"), mapOf("hiddenLocal" to true), false, "gold", "l")
    private val webSettings = obj(
        """{"theme":"dark","posterSize":"m","hero":true,"homeOrder":["r2","r1"],"hiddenCatalogs":{"r3":true,"r4":false},
            "accent":"jade","fontScale":"s","subLangs":["en"],"threshold":0.9,"nested":{"x":[1,2]}}""",
    )

    @Test fun cleanKeysTakeTheServerValues() {
        val m = SettingsSyncLogic.merge(local, webSettings, emptySet())
        assertEquals(listOf("r2", "r1"), m.homeOrder)
        assertEquals(mapOf("r3" to true, "r4" to false), m.hiddenCatalogs)
        assertTrue(m.hero); assertEquals("jade", m.accent); assertEquals("s", m.fontScale)
    }

    @Test fun dirtyKeysKeepLocalAndOthersFollowTheServer() {
        val m = SettingsSyncLogic.merge(local, webSettings, setOf("hero", "accent"))
        assertEquals(false, m.hero); assertEquals("gold", m.accent)
        assertEquals(listOf("r2", "r1"), m.homeOrder); assertEquals("s", m.fontScale)
    }

    @Test fun dirtyHiddenFlagsMergePerCatalogLocalWins() {
        val m = SettingsSyncLogic.merge(local.copy(hiddenCatalogs = mapOf("r3" to false, "x" to true)), webSettings, setOf("hiddenCatalogs"))
        assertEquals(mapOf("r3" to false, "r4" to false, "x" to true), m.hiddenCatalogs)
    }

    @Test fun missingOrInvalidRemoteValuesKeepLocal() {
        val m = SettingsSyncLogic.merge(local, obj("""{"accent":"neon","fontScale":3,"hero":"yes","homeOrder":"nope","hiddenCatalogs":[1]}"""), emptySet())
        assertEquals(local, m)
        assertEquals(local, SettingsSyncLogic.merge(local, JsonObject(emptyMap()), emptySet()))
    }

    @Test fun payloadKeepsUnknownKeysAndNonDirtyKnownKeys() {
        val dirty = setOf("homeOrder", "hero")
        val merged = SettingsSyncLogic.merge(local, webSettings, dirty)
        val p = SettingsSyncLogic.payload(webSettings, merged, dirty)
        // known + dirty: replaced
        assertEquals(listOf("l1", "l2"), p["homeOrder"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(false, p["hero"]!!.jsonPrimitive.boolean)
        // known + clean: exactly as on the server
        assertEquals("jade", p["accent"]!!.jsonPrimitive.content)
        assertEquals(webSettings["hiddenCatalogs"], p["hiddenCatalogs"])
        // unknown (web-only) keys survive untouched, including nested values
        for (k in listOf("theme", "posterSize", "subLangs", "threshold", "nested")) assertEquals(webSettings[k], p[k])
        assertEquals(webSettings.keys, p.keys)
    }

    @Test fun payloadWithNothingDirtyEqualsRemote() {
        assertEquals(webSettings, SettingsSyncLogic.payload(webSettings, SettingsSyncLogic.merge(local, webSettings, emptySet()), emptySet()))
    }

    @Test fun payloadOnEmptyServerWritesOnlyDirtyKeys() {
        val p = SettingsSyncLogic.payload(JsonObject(emptyMap()), local, setOf("accent"))
        assertEquals(setOf("accent"), p.keys)
        assertEquals("gold", p["accent"]!!.jsonPrimitive.content)
    }

    @Test fun resetFlagsPropagateAsFalseNotMissing() {
        val reset = local.copy(homeOrder = emptyList(), hiddenCatalogs = webSettings["hiddenCatalogs"]!!.jsonObject.mapValues { false }, hero = true)
        val dirty = setOf("homeOrder", "hiddenCatalogs", "hero")
        val p = SettingsSyncLogic.payload(webSettings, SettingsSyncLogic.merge(reset, webSettings, dirty), dirty)
        assertEquals(JsonArray(emptyList()), p["homeOrder"])
        assertEquals(false, p["hiddenCatalogs"]!!.jsonObject["r3"]!!.jsonPrimitive.boolean)
        assertEquals(JsonPrimitive(true), p["hero"])
    }

    @Test fun parsersTolerateGarbage() {
        assertEquals(listOf("a", "b"), SettingsSyncLogic.parseHomeOrder(obj("""{"k":["a",1,"b","a"]}""")["k"]))
        assertEquals(mapOf("a" to true), SettingsSyncLogic.parseHidden(obj("""{"k":{"a":true,"b":"x"}}""")["k"]))
    }

    @Test fun mergeOrderKeepsLocalFirstAndAppendsRemoteOnly() {
        assertEquals(listOf("a", "b", "c"), SettingsSyncLogic.mergeOrder(listOf("a", "b"), listOf("c", "a")))
        assertEquals(emptyList<String>(), SettingsSyncLogic.mergeOrder(emptyList(), listOf("x")))
        assertEquals(listOf("a"), SettingsSyncLogic.mergeOrder(listOf("a"), null))
    }

    @Test fun dirtyOrderDoesNotDropRemoteOnlyEntries() {
        val m = SettingsSyncLogic.merge(local, webSettings, setOf(SettingsSyncLogic.HOME_ORDER))
        assertEquals(listOf("l1", "l2", "r2", "r1"), m.homeOrder)
    }

    @Test fun settleKeepsKeyEditedDuringTheRoundAndLeavesItDirty() {
        val snap = local
        val merged = snap.copy(accent = "jade", hero = true)
        val live = snap.copy(accent = "rose") // user edited accent mid-round
        val out = SettingsSyncLogic.settle(live, setOf(SettingsSyncLogic.ACCENT), snap, merged, setOf(SettingsSyncLogic.ACCENT), pushed = true)
        assertEquals("rose", out.settings.accent)
        assertTrue(SettingsSyncLogic.ACCENT in out.dirty)
        assertTrue(out.settings.hero) // untouched key follows merged
    }

    @Test fun settleClearsPushedKeysOnlyWhenPushed() {
        val snap = local
        val merged = snap.copy(accent = "jade")
        val d = setOf(SettingsSyncLogic.ACCENT)
        assertTrue(SettingsSyncLogic.settle(snap, d, snap, merged, d, pushed = true).dirty.isEmpty())
        assertEquals(d, SettingsSyncLogic.settle(snap, d, snap, merged, d, pushed = false).dirty)
    }
}
