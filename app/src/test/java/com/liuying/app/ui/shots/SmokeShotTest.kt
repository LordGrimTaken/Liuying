package com.liuying.app.ui.shots

import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.liuying.app.ui.common.EmptyBlock
import com.liuying.app.ui.theme.LiuyingTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class SmokeShotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun empty() {
        rule.setContent { LiuyingTheme { EmptyBlock("Your shelf is empty", "Add movies and series to your shelf with the + button.") } }
        rule.onRoot().captureRoboImage("build/shots/empty.png")
    }
}
