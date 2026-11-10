package com.liuying.app.ui.shots

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.liuying.app.domain.model.LibraryItem
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class ShelfSelectionShotsTest : ShotBase() {
    private fun settle() { waitFor(1500) { false } }

    @Test fun theSelectionBarOffersBothDirections() {
        val now = System.currentTimeMillis()
        runBlocking {
            container.library.add(LibraryItem("movie", "tt91", "First Film", null, null, "2021", emptyList(), now))
            container.library.add(LibraryItem("movie", "tt92", "Second Film", null, null, "2022", emptyList(), now - 1))
        }
        app()
        settle()
        rule.onNode(hasContentDescription("Shelf")).performClick()
        settle()
        rule.onNode(hasText("First Film")).performTouchInput { longClick() }
        settle()
        rule.onNode(hasText("Select")).performClick()
        settle()
        shot("shelf-selection-360")
    }
}
