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
        return SyncedSettings(
            homeOrder = pick(HOME_ORDER, local.homeOrder, parseHomeOrder(remote[HOME_ORDER])),
            // hidden flags merge per catalog: a catalog only the other device knows keeps its flag
            hiddenCatalogs = if (HIDDEN in dirty) (remoteHidden.orEmpty() + local.hiddenCatalogs) else remoteHidden ?: local.hiddenCatalogs,
            hero = pick(HERO, local.hero, (remote[HERO] as? JsonPrimitive)?.booleanOrNull),
            accent = pick(ACCENT, local.accent, str(remote[ACCENT], ACCENTS)),
            fontScale = pick(FONT_SCALE, local.fontScale, str(remote[FONT_SCALE], FONT_SCALES)),
        )
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
