package com.liuying.app.domain

import com.liuying.app.domain.model.MetaPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankingTest {
    private fun m(id: String, title: String) = MetaPreview(id = id, type = "movie", name = title)

    @Test fun normaliseDropsAccentsPunctuationAndCase() {
        assertEquals("amelie", SearchRanking.normalise("Amélie"))
        assertEquals("spider man", SearchRanking.normalise("Spider-Man!"))
        assertEquals("the matrix", SearchRanking.normalise("  The   Matrix  "))
        assertEquals("龙之纪元", SearchRanking.normalise("龙之纪元"))
    }

    @Test fun exactBeatsEverything() {
        assertEquals(0, SearchRanking.score("Dune", "dune"))
        assertTrue(SearchRanking.score("Dune", "dune") < SearchRanking.score("Dune Part Two", "dune"))
    }

    @Test fun anArticleInEitherPositionStillMatchesExactly() {
        assertEquals(1, SearchRanking.score("The Matrix", "matrix"))
        assertEquals(1, SearchRanking.score("Matrix, The", "the matrix"))
        assertEquals(1, SearchRanking.score("A Quiet Place", "quiet place"))
    }

    @Test fun spacingAndPunctuationDoNotHideAnExactTitle() {
        // The user types the title without the punctuation the catalog uses.
        assertEquals(1, SearchRanking.score("Spider-Man", "spiderman"))
        assertEquals(0, SearchRanking.score("Spider Man", "spider-man"))   // the same string once normalised
        assertEquals(2, SearchRanking.score("Spiderman: No Way Home", "spiderman"))
        assertEquals(4, SearchRanking.score("The Amazing Spider-Man", "spiderman"))
    }

    @Test fun wordStartBeatsASubstringInTheMiddle() {
        assertEquals(3, SearchRanking.score("The Matrix Reloaded", "reloaded"))
        assertEquals(4, SearchRanking.score("Unreloaded", "reloaded"))
        assertTrue(SearchRanking.score("Star Wars", "star") < SearchRanking.score("Lone Star", "star"))
    }

    @Test fun cjkTitlesMatchWithoutWordBoundaries() {
        assertEquals(0, SearchRanking.score("龙之纪元", "龙之纪元"))
        assertEquals(2, SearchRanking.score("龙之纪元：暗影王子", "龙之纪元"))
        assertEquals(3, SearchRanking.score("暗影王子：龙之纪元", "龙之纪元"))   // punctuation separates the words
    }

    @Test fun anEmptyQueryScoresWorstInsteadOfThrowing() {
        assertEquals(5, SearchRanking.score("Dune", ""))
        assertEquals(5, SearchRanking.score("", "dune"))
    }

    @Test fun rankIsStableForTies() {
        val a = m("a", "Dune")
        val b = m("b", "Dune")
        val c = m("c", "Dune Part Two")
        assertEquals(listOf(a, b, c), SearchRanking.rank(listOf(c, a, b), "dune"))
    }

    @Test fun emptyGroupsProduceNothing() {
        assertTrue(SearchRanking.bestMatches(emptyList(), "dune").isEmpty())
        assertTrue(SearchRanking.orderGroups(emptyList(), "dune").isEmpty())
        assertTrue(SearchRanking.sourceNames(emptyList()).isEmpty())
    }

    @Test fun suggestionsAdaptToTheQuery() {
        assertTrue(SearchRanking.suggestions("one").first().contains("spelling"))
        assertTrue(SearchRanking.suggestions("two words").first().contains("two words".split(" ").maxByOrNull { it.length }!!))
        assertTrue(SearchRanking.suggestions("three word query").first().contains("fewer words"))
    }
}
