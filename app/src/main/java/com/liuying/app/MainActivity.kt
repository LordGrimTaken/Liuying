package com.liuying.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuying.app.data.settings.AppSettings
import com.liuying.app.ui.common.LocalContainer
import com.liuying.app.ui.nav.LiuyingApp
import com.liuying.app.ui.theme.LiuyingTheme

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        // Back from the "install unknown apps" screen: continue the pending update install.
        (application as LiuyingApplication).container.updates.resumeInstallIfPending()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val container = (application as LiuyingApplication).container
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(AppSettings())
            LiuyingTheme(settings.accent, settings.fontScale) {
                CompositionLocalProvider(LocalContainer provides container) { LiuyingApp()
                    com.liuying.app.ui.update.UpdateDialog(container.updates)
                }
            }
        }
    }
}
