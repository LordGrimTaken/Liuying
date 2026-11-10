package com.liuying.app.domain

import com.liuying.app.data.repo.RowState
import com.liuying.app.data.repo.SearchGroup
import com.liuying.app.domain.model.MetaPreview
import java.text.Normalizer

/** Exact-title-first search ordering (L006). Pure. */
object SearchRanking {
    /** Lowercase, accents and punctuation removed, spaces collapsed. */
    fun normalise(s: String): String {
        val d = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return d.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    private val articles = listOf("the ", "a ", "an ")

    /**
     * Canonical form without a leading or trailing article: "the matrix", "matrix" and "matrix, the" all become
     * "matrix", so the same title typed either way is still an exact match.
     */
    internal fun dropArticle(s: String): String {
        var x = s
        articles.firstOrNull { x.startsWith(it) }?.let { x = x.removePrefix(it) }
        for (a in listOf(" the", " a", " an")) {
            if (x.endsWith(a)) { x = a.trim() + " " + x.removeSuffix(a); break }
        }
        articles.firstOrNull { x.startsWith(it) }?.let { x = x.removePrefix(it) }
        return x
    }

    /** "spider man" and "spiderman" are the same title typed two ways; punctuation and spaces are not signal. */
    internal fun compact(s: String): String = s.replace(" ", "")

    /** 0 exact, 1 exact ignoring an article or spacing, 2 starts with the query, 3 a word starts with it, 4 contains it, 5 other. */
    fun score(title: String, query: String): Int {
        val t = normalise(title)
        val q = normalise(query)
        if (q.isEmpty() || t.isEmpty()) return 5
        val ct = compact(t)
        val cq = compact(q)
        return when {
            t == q -> 0
            dropArticle(t) == dropArticle(q) -> 1
            // "Spider-Man" vs "spiderman": same title, different punctuation or spacing.
            ct == cq -> 1
            t.startsWith(q) || ct.startsWith(cq) -> 2
            (" $t").contains(" $q") -> 3
            t.contains(q) || ct.contains(cq) -> 4
            else -> 5
        }
    }

    /** Stable: ties keep the source's own order. */
    fun rank(items: List<MetaPreview>, query: String): List<MetaPreview> =
        items.withIndex().sortedWith(compareBy({ score(it.value.name, query) }, { it.index })).map { it.value }

    fun bestScore(g: SearchGroup, query: String): Int =
        (g.state as? RowState.Loaded)?.items?.minOfOrNull { score(it.name, query) } ?: 6

    /** Rows with the best title match first; equal rows keep their order. Items inside each loaded row are ranked too. */
    fun orderGroups(groups: List<SearchGroup>, query: String): List<SearchGroup> {
        val ranked = groups.map { g ->
            val st = g.state
            if (st is RowState.Loaded) g.copy(state = RowState.Loaded(rank(st.items, query))) else g
        }
        return ranked.withIndex().sortedWith(compareBy({ bestScore(it.value, query) }, { it.index })).map { it.value }
    }

    /** Exact (or article-less exact) matches across all rows, de-duplicated by id: the "Best match" row. */
    fun bestMatches(groups: List<SearchGroup>, query: String): List<Pair<MetaPreview, SearchGroup>> {
        val seen = HashSet<String>()
        val out = ArrayList<Pair<MetaPreview, SearchGroup>>()
        for (g in groups) {
            val st = g.state as? RowState.Loaded ?: continue
            for (m in st.items) if (score(m.name, query) <= 1 && seen.add(m.id)) out += m to g
        }
        return out
    }

    /** Source names that have at least one result, for the per-source filter chips. */
    fun sourceNames(groups: List<SearchGroup>): List<String> =
        groups.filter { it.hasItems }.map { it.entry.source.displayName }.distinct()

    /** Two concrete suggestions for the no-results card (L009). */
    fun suggestions(query: String): List<String> {
        val words = normalise(query).split(' ').filter { it.isNotBlank() }
        val out = ArrayList<String>()
        if (words.size > 2) out += "Try fewer words, for example \"${words.take(2).joinToString(" ")}\"."
        else if (words.size == 2) out += "Try just \"${words.maxByOrNull { it.length }}\"."
        else out += "Check the spelling, or try the title in its original language."
        out += "Make sure at least one enabled source supports search (Sources lists what each one offers)."
        return out
    }
}
