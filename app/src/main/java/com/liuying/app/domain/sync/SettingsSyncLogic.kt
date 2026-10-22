package com.liuying.app.domain.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The slice of the account settings object this app understands; the keys match the web player's settings. */
data class SyncedSettings(
    val homeOrder: List<String>,
    val hiddenCatalogs: Map<String, Boolean>,
    val hero: Boolean,
    val accent: String,
    val fontScale: String,
) {
    companion object {
        val DEFAULT = SyncedSettings(emptyList(), emptyMap(), true, "vermilion", "m")
    }
}

/** What a finished sync round leaves in the settings store: the values to keep and the keys still waiting to be pushed. */
data class RoundOutcome(val settings: SyncedSettings, val dirty: Set<String>)

/**
 * Settings sync rules. The server stores one opaque JSON object per account (PUT replaces it), shared with the web
 * player. So this app only ever edits the keys it knows ([KEYS]) and carries every other key through untouched.
 * A key edited here and not yet pushed is "dirty": local wins for dirty keys, the server wins for the rest.
 */
object SettingsSyncLogic {
    const val HOME_ORDER = "homeOrder"
    const val HIDDEN = "hiddenCatalogs"
    const val HERO = "hero"
    const val ACCENT = "accent"
    const val FONT_SCALE = "fontScale"
    val KEYS = setOf(HOME_ORDER, HIDDEN, HERO, ACCENT, FONT_SCALE)
    val ACCENTS = listOf("vermilion", "gold", "jade", "indigo")
    val FONT_SCALES = listOf("s", "m", "l")
    private const val MAX_ORDER = 300
    private const val MAX_HIDDEN = 600

    fun parseHomeOrder(el: JsonElement?): List<String>? =
        (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }?.distinct()?.take(MAX_ORDER)

    fun parseHidden(el: JsonElement?): Map<String, Boolean>? {
        val o = el as? JsonObject ?: return null
        val out = LinkedHashMap<String, Boolean>()
        for ((k, v) in o) { (v as? JsonPrimitive)?.booleanOrNull?.let { if (out.size < MAX_HIDDEN) out[k] = it } }
        return out
    }

    private fun str(el: JsonElement?, allowed: List<String>): String? =
        (el as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it in allowed }

    /** Result of a pull: remote values for clean keys, local values for dirty ones. */
    fun merge(local: SyncedSettings, remote: JsonObject, dirty: Set<String>): SyncedSettings {
        fun <T> pick(key: String, localValue: T, remoteValue: T?): T = if (key in dirty) localValue else remoteValue ?: localValue
        val remoteHidden = parseHidden(remote[HIDDEN])
        val remoteOrder = parseHomeOrder(remote[HOME_ORDER])
        return SyncedSettings(
            // Our order wins, but catalogs only the other device knows about stay in the list (after ours) instead of being dropped.
            homeOrder = if (HOME_ORDER in dirty) mergeOrder(local.homeOrder, remoteOrder) else remoteOrder ?: local.homeOrder,
            // hidden flags merge per catalog: a catalog only the other device knows keeps its flag
            hiddenCatalogs = if (HIDDEN in dirty) (remoteHidden.orEmpty() + local.hiddenCatalogs) else remoteHidden ?: local.hiddenCatalogs,
            hero = pick(HERO, local.hero, (remote[HERO] as? JsonPrimitive)?.booleanOrNull),
            accent = pick(ACCENT, local.accent, str(remote[ACCENT], ACCENTS)),
            fontScale = pick(FONT_SCALE, local.fontScale, str(remote[FONT_SCALE], FONT_SCALES)),
        )
    }

    /**
     * [local] first, then every key of [remote] that [local] lacks, in the server's order. An empty [local] order means
     * "back to default" and is pushed as such (the user reset the layout), so it is not refilled from the server.
     */
    fun mergeOrder(local: List<String>, remote: List<String>?): List<String> {
        if (local.isEmpty() || remote.isNullOrEmpty()) return local
        val have = local.toHashSet()
        return (local + remote.filter { it !in have }).distinct().take(MAX_ORDER)
    }

    private fun valueOf(s: SyncedSettings, key: String): Any = when (key) {
        HOME_ORDER -> s.homeOrder
        HIDDEN -> s.hiddenCatalogs
        HERO -> s.hero
        ACCENT -> s.accent
        else -> s.fontScale
    }

    private fun withValue(s: SyncedSettings, key: String, from: SyncedSettings): SyncedSettings = when (key) {
        HOME_ORDER -> s.copy(homeOrder = from.homeOrder)
        HIDDEN -> s.copy(hiddenCatalogs = from.hiddenCatalogs)
        HERO -> s.copy(hero = from.hero)
        ACCENT -> s.copy(accent = from.accent)
        else -> s.copy(fontScale = from.fontScale)
    }

    /**
     * Applied atomically to the live settings when a sync round ends. The round worked from [snapshot]; if the user changed
     * a key since then ([live] differs from the snapshot) that newer edit is kept and the key stays dirty, so it is pushed
     * on the next round instead of being overwritten by (or marked as already synced with) the older value.
     * Keys that did not change take the [merged] value, and leave the dirty set only if they were actually [pushed].
     */
    fun settle(live: SyncedSettings, liveDirty: Set<String>, snapshot: SyncedSettings, merged: SyncedSettings, dirtyAtStart: Set<String>, pushed: Boolean): RoundOutcome {
        var out = live
        val dirty = liveDirty.toMutableSet()
        for (k in KEYS) {
            if (valueOf(live, k) != valueOf(snapshot, k)) { dirty += k; continue }
            out = withValue(out, k, merged)
            if (k in dirtyAtStart && pushed) dirty -= k
        }
        return RoundOutcome(out, dirty)
    }

    /**
     * The object to PUT: [remote] with only the dirty keys replaced by the merged values. Unknown keys, and known keys
     * that are not dirty, stay exactly as the server has them.
     */
    fun payload(remote: JsonObject, merged: SyncedSettings, dirty: Set<String>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(remote)
        if (HOME_ORDER in dirty) out[HOME_ORDER] = JsonArray(merged.homeOrder.map(::JsonPrimitive))
        if (HIDDEN in dirty) out[HIDDEN] = JsonObject(merged.hiddenCatalogs.mapValues { JsonPrimitive(it.value) })
        if (HERO in dirty) out[HERO] = JsonPrimitive(merged.hero)
        if (ACCENT in dirty) out[ACCENT] = JsonPrimitive(merged.accent)
        if (FONT_SCALE in dirty) out[FONT_SCALE] = JsonPrimitive(merged.fontScale)
        return JsonObject(out)
    }
}
