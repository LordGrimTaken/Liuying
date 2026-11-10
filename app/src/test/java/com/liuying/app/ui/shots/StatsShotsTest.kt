package com.liuying.app.ui.shots

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performClick
import com.liuying.app.data.db.ProgressEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class StatsShotsTest : ShotBase() {
    private fun settle() { waitFor(1500) { false } }

    @Test fun youViewingStats() {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        runBlocking {
            val dao = container.db.progressDao()
            // Two finished films today and yesterday, two finished episodes today: 2h + 1h30m + 45m + 45m.
            dao.upsert(ProgressEntity("movie", "tt1", -1, -1, "tt1", 0, 7_200_000, true, now))
            dao.upsert(ProgressEntity("movie", "tt2", -1, -1, "tt2", 0, 5_400_000, true, now - day))
            dao.upsert(ProgressEntity("series", "ttS1", 1, 1, "ttS1:1:1", 0, 2_700_000, true, now))
            dao.upsert(ProgressEntity("series", "ttS1", 1, 2, "ttS1:1:2", 0, 2_700_000, true, now))
        }
        app()
        settle()
        rule.onNode(hasContentDescription("You")).performClick()
        settle()
        shot("you-viewing-stats-360")
    }
}
