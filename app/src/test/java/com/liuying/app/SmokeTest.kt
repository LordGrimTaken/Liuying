package com.liuying.app

import com.liuying.app.ui.nav.Overlay
import com.liuying.app.ui.nav.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SmokeTest {
    @Test
    fun bottomNavHasFiveTabsInOrder() {
        assertEquals(listOf("Today", "Movies", "Series", "Shelf", "You"), Tab.entries.map { it.label })
    }

    @Test
    fun searchAndCalendarLiveInTheTopBarNotTheNav() {
        val tabRoutes = Tab.entries.map { it.route }
        assertFalse(Overlay.SEARCH in tabRoutes)
        assertFalse(Overlay.CALENDAR in tabRoutes)
        assertEquals(tabRoutes.size, tabRoutes.toSet().size)
    }
}
