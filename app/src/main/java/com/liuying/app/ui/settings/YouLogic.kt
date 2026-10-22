package com.liuying.app.ui.settings

import com.liuying.app.data.repo.InstalledPlugin

/** Pure helpers behind the You screen: one-line section summaries, grouping, filtering and remembered expansion. */
object YouLogic {
    /** Section ids, in display order. */
    val SECTIONS = listOf("account", "sources", "appearance", "layout", "playback", "content", "repos", "integrations", "data", "about")
    val DEFAULT_OPEN = setOf("account")

    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    fun accountSummary(username: String?) = if (username == null) "Not signed in" else "Signed in as $username"

    fun sourcesSummary(count: Int, enabled: Int = count): String = when {
        count == 0 -> "None added"
        enabled == count -> "$count added"
        else -> "$count added, $enabled on"
    }

    fun layoutSummary(total: Int, hidden: Int): String = when {
        total == 0 -> "No catalogs yet"
        hidden == 0 -> plural(total, "catalog")
        else -> "${plural(total, "catalog")}, $hidden hidden"
    }

    fun reposSummary(repos: Int, plugins: Int, enabled: Int): String =
        if (repos == 0) "None added" else "${plural(repos, "repo")}, ${plural(plugins, "plugin")}, $enabled enabled"

    fun playbackSummary(streamChoice: String, seekSec: Int) = "$streamChoice, $seekSec s seek"
    fun appearanceSummary(accent: String, textSize: String) = "$accent accent, ${textSize.lowercase()} text"
    fun contentSummary(adultHidden: Boolean, perRow: Int) = (if (adultHidden) "Adult hidden" else "Adult shown") + ", $perRow per row"
    fun integrationsSummary(hasKey: Boolean) = if (hasKey) "Metadata key set" else "No key set"

    // ------------------------------------------------------------------------------------ Today layout

    data class Group<T>(val source: String, val rows: List<T>)

    /** Catalog rows grouped by source, groups in order of first appearance and rows keeping their order. */
    fun <T> groupBySource(rows: List<T>, source: (T) -> String): List<Group<T>> {
        val out = LinkedHashMap<String, MutableList<T>>()
        for (r in rows) out.getOrPut(source(r)) { ArrayList() }.add(r)
        return out.map { Group(it.key, it.value) }
    }

    /**
     * The swap distance (in the full order [allKeys]) that moves [key] one place up (-1) or down (+1) among its own group's
     * keys [groupKeys], or null when it is already first/last in the group.
     */
    fun moveDeltaWithinGroup(allKeys: List<String>, groupKeys: List<String>, key: String, dir: Int): Int? {
        val gi = groupKeys.indexOf(key)
        val target = groupKeys.getOrNull(gi + dir) ?: return null
        val from = allKeys.indexOf(key)
        val to = allKeys.indexOf(target)
        if (gi < 0 || from < 0 || to < 0) return null
        return to - from
    }

    // ------------------------------------------------------------------------------------ repositories

    /** Plugins whose name, id or description contains [query] (case-insensitive); a blank query keeps everything. */
    fun filterPlugins(plugins: List<InstalledPlugin>, query: String): List<InstalledPlugin> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return plugins
        return plugins.filter { p -> q in p.info.name.lowercase() || q in p.info.id.lowercase() || q in p.info.description.lowercase() }
    }

    // ------------------------------------------------------------------------------------ remembered expansion

    fun encodeOpen(open: Set<String>): String = SECTIONS.filter { it in open }.joinToString(",")

    /** Unknown ids are dropped; null/blank input (nothing stored yet) gives [default]; an explicitly empty store means all closed. */
    fun decodeOpen(stored: String?, default: Set<String> = DEFAULT_OPEN): Set<String> {
        if (stored == null) return default
        return stored.split(',').map { it.trim() }.filter { it in SECTIONS }.toSet()
    }
}
