package com.liuying.app.data.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TmdbResolverTest {
    private val body = """{"movie_results":[{"id":603,"title":"The Matrix"}],"tv_results":[{"id":1396}],"person_results":[]}"""
    @Test fun picksTheResultForTheMediaType() {
        assertEquals("603", TmdbResolver.parseFind(body, "movie")); assertEquals("1396", TmdbResolver.parseFind(body, "tv"))
    }
    @Test fun emptyOrBrokenAnswersGiveNull() {
        assertNull(TmdbResolver.parseFind("""{"movie_results":[]}""", "movie")); assertNull(TmdbResolver.parseFind("nope", "tv")); assertNull(TmdbResolver.parseFind("""{"tv_results":[{"id":"x"}]}""", "tv"))
    }
    @Test fun withoutAKeyTheImdbIdIsPassedThrough() = kotlinx.coroutines.runBlocking {
        val r = TmdbResolver(okhttp3.OkHttpClient(), apiKey = { "" })
        assertEquals("tt0133093", r.resolve("tt0133093", "movie"))
    }
}
