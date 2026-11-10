package com.liuying.app.ui.shots

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import com.liuying.app.data.db.ProgressEntity
import com.liuying.app.data.repo.RoomMetaStore
import com.liuying.app.domain.model.Meta
import com.liuying.app.domain.model.Video
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class TypePageHideWatchedTest : ShotBase() {
    private val finished = "龙之纪元：暗影王子 1"

    private fun seedFinishedSeries() {
        val now = System.currentTimeMillis()
        runBlocking {
            RoomMetaStore(container.db.metaCacheDao()).put(
                "series", "ttS1",
                Meta(id = "ttS1", type = "series", name = "Show", videos = listOf(Video(id = "ttS1:1:1", season = 1, episode = 1), Video(id = "ttS1:1:2", season = 1, episode = 2))),
                now,
            )
            container.db.progressDao().upsert(ProgressEntity("series", "ttS1", 1, 1, "ttS1:1:1", 0, 2_700_000, true, now))
            container.db.progressDao().upsert(ProgressEntity("series", "ttS1", 1, 2, "ttS1:1:2", 0, 2_700_000, true, now))
        }
    }

    private fun openSeriesTab(hideWatched: Boolean) {
        runBlocking { container.settings.update { it.copy(hideWatchedInBrowse = hideWatched) } }
        app()
        waitFor(8000) { addon.hits.any { it.contains("/catalog/series/") } }
        rule.onNode(hasContentDescription("Series")).performClick()
        waitFor(8000) { addon.hits.any { it.contains("/catalog/series/") } }
        waitFor(1500) { false }
    }

    @Test fun hideWatchedRemovesAFinishedSeriesFromTheSeriesTab() {
        seedSource()
        seedFinishedSeries()
        openSeriesTab(hideWatched = true)
        assertEquals("the finished series must be hidden", 0, rule.onAllNodes(hasText(finished)).fetchSemanticsNodes().size)
        assertEquals("other titles stay", 1, rule.onAllNodes(hasText("Title 2")).fetchSemanticsNodes().size)
    }

    @Test fun withTheSwitchOffTheFinishedSeriesStaysVisible() {
        seedSource()
        seedFinishedSeries()
        openSeriesTab(hideWatched = false)
        assertEquals("the finished series must be visible", 1, rule.onAllNodes(hasText(finished)).fetchSemanticsNodes().size)
    }
}
