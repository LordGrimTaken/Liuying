package com.liuying.app.ui.shots

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class ScreensShotsTest : ShotBase() {
    private fun settle() { waitFor(1500) { false } }
    private fun tab(name: String) { rule.onNode(hasContentDescription(name)).performClick(); settle() }

    @Test fun tabsAtLargeFont() {
        addon.hugeTitles = true
        seedSource()
        app(fontScale = 1.3f, textSize = "l")
        settle(); shot("today-font13")
        tab("Movies"); shot("movies-font13")
        tab("Series"); shot("series-font13")
        tab("Shelf"); shot("shelf-empty-font13")
        tab("You"); shot("you-font13")
        tab("Calendar"); shot("calendar-empty-font13")
    }

    @Test fun detailsMovieAndSeries() {
        seedSource()
        app()
        settle()
        rule.onAllNodes(hasText("Title 2")).onFirst().performClick(); settle(); shot("details-movie")
    }

    @Test fun searchOverlay() {
        seedSource()
        app()
        settle()
        rule.onNode(hasContentDescription("Search")).performClick(); settle(); shot("search-empty")
    }

    @Test fun offlineToday() {
        seedSource()
        addon.offline = true
        app()
        settle(); shot("today-offline")
    }
}
