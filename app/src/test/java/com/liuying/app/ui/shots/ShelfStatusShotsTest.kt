package com.liuying.app.ui.shots

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import com.liuying.app.data.db.ProgressEntity
import com.liuying.app.data.repo.RoomMetaStore
import com.liuying.app.domain.model.LibraryItem
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
class ShelfStatusShotsTest : ShotBase() {
    private fun settle() { waitFor(1500) { false } }

    @Test fun aFinishedSeriesShowsUnderCompleted() {
        val now = System.currentTimeMillis()
        runBlocking {
            val meta = Meta(
                id = "ttS1", type = "series", name = "Finished Show",
                videos = listOf(
                    Video(id = "ttS1:1:1", season = 1, episode = 1),
                    Video(id = "ttS1:1:2", season = 1, episode = 2),
                ),
            )
            RoomMetaStore(container.db.metaCacheDao()).put("series", "ttS1", meta, now)
            container.library.add(LibraryItem("series", "ttS1", "Finished Show", null, null, "2021", emptyList(), now))
            container.library.add(LibraryItem("series", "ttS2", "Half Watched", null, null, "2022", emptyList(), now))
            val dao = container.db.progressDao()
            dao.upsert(ProgressEntity("series", "ttS1", 1, 1, "ttS1:1:1", 0, 2_700_000, true, now))
            dao.upsert(ProgressEntity("series", "ttS1", 1, 2, "ttS1:1:2", 0, 2_700_000, true, now))
            dao.upsert(ProgressEntity("series", "ttS2", 1, 1, "ttS2:1:1", 0, 2_700_000, true, now))
        }
        app()
        settle()
        rule.onNode(hasContentDescription("Shelf")).performClick()
        settle()
        rule.onNode(hasText("Completed")).performClick()
        settle()
        shot("shelf-completed-series-360")
    }
}
