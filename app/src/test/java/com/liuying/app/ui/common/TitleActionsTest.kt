package com.liuying.app.ui.common

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.liuying.app.AppContainer
import com.liuying.app.data.db.ProgressEntity
import com.liuying.app.data.repo.RoomMetaStore
import com.liuying.app.domain.WatchedIndex
import com.liuying.app.domain.model.Meta
import com.liuying.app.domain.model.MetaPreview
import com.liuying.app.domain.model.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The badge source: which titles count as watched, including a series whose episode list is fully watched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TitleActionsTest {
    private fun container() = AppContainer(ApplicationProvider.getApplicationContext<Application>())

    @Test fun aFinishedSeriesCountsAsWatchedOnlyWhenItsListIsKnown() = runBlocking {
        val c = container()
        val now = System.currentTimeMillis()
        val dao = c.db.progressDao()
        dao.upsert(ProgressEntity("series", "ttS1", 1, 1, "ttS1:1:1", 0, 2_700_000, true, now))
        dao.upsert(ProgressEntity("series", "ttS1", 1, 2, "ttS1:1:2", 0, 2_700_000, true, now))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actions = TitleActions(c, scope)
        val subscription = scope.launch { actions.watchedKeys.collect { } }
        delay(400)
        val key = WatchedIndex.key("series", "ttS1")

        // Without a cached episode list the app cannot know the series is finished.
        assertFalse("no episode list yet", key in actions.watchedKeys.value)

        RoomMetaStore(c.db.metaCacheDao()).put(
            "series", "ttS1",
            Meta(id = "ttS1", type = "series", name = "Show", videos = listOf(Video(id = "ttS1:1:1", season = 1, episode = 1), Video(id = "ttS1:1:2", season = 1, episode = 2))),
            now,
        )
        delay(400)
        assertTrue("the whole list is watched", key in actions.watchedKeys.value)

        subscription.cancel()
    }

    @Test fun aSeriesWithoutAnEpisodeListSaysWhyNothingHappened() = runBlocking {
        val c = container()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actions = TitleActions(c, scope)
        actions.toggleWatched(MetaPreview(id = "ttS9", type = "series", name = "Unknown Show"), "series")
        withTimeoutOrNull(5_000) { while (actions.message.value == null) delay(50) }
        val msg = actions.message.value
        assertTrue("message was $msg", msg != null && msg.contains("Could not load the episode list"))
    }

    @Test fun markingASeriesReportsHowManyEpisodesChanged() = runBlocking {
        val c = container()
        val now = System.currentTimeMillis()
        RoomMetaStore(c.db.metaCacheDao()).put(
            "series", "ttS7",
            Meta(id = "ttS7", type = "series", name = "Show", videos = listOf(Video(id = "ttS7:1:1", season = 1, episode = 1), Video(id = "ttS7:1:2", season = 1, episode = 2))),
            now,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actions = TitleActions(c, scope)
        val subscription = scope.launch { actions.watchedKeys.collect { } }
        delay(300)
        val preview = MetaPreview(id = "ttS7", type = "series", name = "Show")

        actions.toggleWatched(preview, "series")
        withTimeoutOrNull(5_000) { while (actions.message.value == null) delay(50) }
        assertTrue("first: ${actions.message.value}", actions.message.value!!.contains("Marked 2 episodes watched"))
        actions.clearMessage()
        delay(400)

        actions.toggleWatched(preview, "series")
        withTimeoutOrNull(5_000) { while (actions.message.value == null) delay(50) }
        assertTrue("second: ${actions.message.value}", actions.message.value!!.contains("Marked 2 episodes unwatched"))
        subscription.cancel()
    }
}
