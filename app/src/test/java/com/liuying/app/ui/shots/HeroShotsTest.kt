package com.liuying.app.ui.shots

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
class HeroShotsTest : ShotBase() {
    private fun settle() { waitFor(1500) { false } }

    @Test fun theHeroSkipsATitleTheUserAlreadyWatched() {
        seedSource()
        val now = System.currentTimeMillis()
        runBlocking {
            // tt1 is the first item of the first catalog, so it would be the hero unless it is skipped.
            container.db.progressDao().upsert(ProgressEntity("movie", "tt1", -1, -1, "tt1", 0, 7_200_000, true, now))
        }
        app()
        waitFor(8000) { addon.hits.any { it.contains("/catalog/") } }
        settle()
        shot("today-hero-skips-watched-360")
    }
}
