package com.liuying.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.liuying.app.ui.home.TodayScreen
import com.liuying.app.ui.library.ShelfScreen
import com.liuying.app.ui.search.SearchScreen
import com.liuying.app.ui.settings.YouScreen

/** Height reserved at the bottom of each screen so content clears the floating dock. */
val DockClearance = 108.dp

@Composable
fun LiuyingApp() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = Tab.entries.firstOrNull { it.route == entry?.destination?.route }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavHost(
            navController = nav,
            startDestination = Tab.Today.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Tab.Today.route) { TodayScreen() }
            composable(Tab.Search.route) { SearchScreen() }
            composable(Tab.Shelf.route) { ShelfScreen() }
            composable(Tab.You.route) { YouScreen() }
        }
        InkDock(
            current = current,
            onSelect = { tab ->
                nav.navigate(tab.route) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        )
    }
}
