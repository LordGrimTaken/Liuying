package com.liuying.app

import com.liuying.app.ui.nav.Tab
import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun dockHasFiveTabsInOrder() {
        assertEquals(listOf("Today", "Search", "Shelf", "Calendar", "You"), Tab.entries.map { it.label })
    }
}
