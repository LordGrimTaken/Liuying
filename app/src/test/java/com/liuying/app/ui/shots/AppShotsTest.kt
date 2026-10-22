package com.liuying.app.ui.shots

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class AppShotsTest : ShotBase() {
    @Test fun todayEmpty() {
        app()
        waitFor { false.also { Thread.sleep(300) } }
        shot("today-empty-360")
    }

    @Test fun todayWithSource() {
        seedSource()
        app()
        waitFor(6000) { addon.hits.any { it.contains("/catalog/") } }
        Thread.sleep(500); waitFor(1500) { false }
        shot("today-360")
    }
}
