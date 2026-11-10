package com.liuying.app.ui.shots

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.liuying.app.data.db.ProgressEntity
import com.liuying.app.data.repo.RoomMetaStore
import com.liuying.app.domain.model.Meta
import com.liuying.app.domain.model.Video
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class SeriesBadgeShotsTest : ShotBase() {
    private fun settle() { waitFor(1500) { false } }

    @Test fun aFinishedSeriesShowsSeenInACatalogRow() {
        val now = System.currentTimeMillis()
        seedSource()
        runBlocking {
            val meta = Meta(
                id = "ttS1", type = "series", name = "Finished Show",
                videos = listOf(Video(id = "ttS1:1:1", season = 1, episode = 1), Video(id = "ttS1:1:2", season = 1, episode = 2)),
            )
            RoomMetaStore(container.db.metaCacheDao()).put("series", "ttS1", meta, now)
            container.db.progressDao().upsert(ProgressEntity("series", "ttS1", 1, 1, "ttS1:1:1", 0, 2_700_000, true, now))
            container.db.progressDao().upsert(ProgressEntity("series", "ttS1", 1, 2, "ttS1:1:2", 0, 2_700_000, true, now))
        }
        app()
        settle()
        rule.onNode(hasContentDescription("Series")).performClick()
        waitFor(8000) { addon.hits.any { it.contains("/catalog/series/") } }
        settle()
        val seen = rule.onAllNodesWithText("Seen").fetchSemanticsNodes()
        org.junit.Assert.assertTrue("expected a Seen badge on the finished series, found ${seen.size}", seen.isNotEmpty())
        shot("browse-series-watched-360")
    }
}
