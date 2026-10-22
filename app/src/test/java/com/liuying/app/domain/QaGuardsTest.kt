package com.liuying.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QaGuardsTest {
    @Test fun cjkSingleCharacterIsSearchableButLatinNeedsTwo() {
        assertTrue(QaGuards.isSearchable("龙"))
        assertTrue(QaGuards.isSearchable("  鬼 "))
        assertTrue(QaGuards.isSearchable("あ"))
        assertTrue(QaGuards.isSearchable("한"))
        assertFalse(QaGuards.isSearchable("a"))
        assertFalse(QaGuards.isSearchable(" "))
        assertFalse(QaGuards.isSearchable(""))
        assertTrue(QaGuards.isSearchable("up"))
        assertEquals(2, QaGuards.minSearchLength(""))
    }

    @Test fun externalUrlSchemesAreWhitelisted() {
        assertTrue(QaGuards.isSafeExternalUrl("https://example.com/x"))
        assertTrue(QaGuards.isSafeExternalUrl("HTTP://example.com"))
        assertTrue(QaGuards.isSafeExternalUrl("stremio://detail/movie/tt1"))
        assertTrue(QaGuards.isSafeExternalUrl("magnet:?xt=urn:btih:abc"))
        for (bad in listOf("file:///sdcard/x", "content://x/y", "intent://x#Intent;end", "tel:123", "sms:1", "javascript:alert(1)", "market://details?id=x", "example.com", "", "https:")) {
            assertFalse(bad, QaGuards.isSafeExternalUrl(bad))
        }
        assertFalse(QaGuards.isSafeExternalUrl(null))
    }

    @Test fun duplicateOptionsAreRemoved() {
        assertEquals(listOf("a", "b"), QaGuards.distinctOptions(listOf("a", "", "b", "a")))
    }
}
