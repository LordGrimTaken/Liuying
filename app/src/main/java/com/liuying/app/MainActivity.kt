package com.liuying.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.CompositionLocalProvider
import com.liuying.app.ui.common.LocalContainer
import com.liuying.app.ui.nav.LiuyingApp
import com.liuying.app.ui.theme.LiuyingTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val container = (application as LiuyingApplication).container
        setContent {
            LiuyingTheme {
                CompositionLocalProvider(LocalContainer provides container) { LiuyingApp()
                    com.liuying.app.ui.update.UpdateDialog(container.updates)
                }
            }
        }
    }
}
