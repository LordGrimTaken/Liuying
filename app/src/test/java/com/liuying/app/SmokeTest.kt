package com.liuying.app

import com.liuying.app.ui.nav.Tab
import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun dockHasFourTabsInOrder() {
        assertEquals(listOf("Today", "Search", "Shelf", "You"), Tab.entries.map { it.label })
    }
}
