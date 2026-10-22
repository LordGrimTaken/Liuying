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
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.liuying.app.AppContainer
import com.liuying.app.domain.model.MetaPreview
import com.liuying.app.ui.common.LocalContainer
import com.liuying.app.ui.details.DetailsScreen
import com.liuying.app.ui.home.TodayScreen
import com.liuying.app.ui.calendar.CalendarScreen
import com.liuying.app.ui.library.ShelfScreen
import com.liuying.app.ui.search.SearchScreen
import com.liuying.app.ui.settings.YouScreen

/** Height reserved at the bottom of each screen so content clears the floating dock. */
val DockClearance = 108.dp

/** Opens the details page, remembering the list item it came from so it can render before the network answers. */
class Navigator(private val nav: NavHostController, private val container: AppContainer) {
    fun openDetails(preview: MetaPreview, fallbackType: String, sourceId: Long?) {
        val type = preview.type.ifBlank { fallbackType }
        container.putHint(type, preview.copy(type = type), sourceId)
        nav.navigate(Routes.details(type, preview.id))
    }

    fun openTitle(type: String, id: String, play: String? = null) = nav.navigate(Routes.details(type, id, play))
    fun back() { nav.popBackStack() }
    /** The full-screen search overlay: it covers the dock and Back returns to where the user was. */
    fun openSearch() { nav.navigate(Tab.Search.route) { launchSingleTop = true } }
    fun goTab(tab: Tab) = nav.navigate(tab.route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun LiuyingApp() {
    val nav = rememberNavController()
    val container = LocalContainer.current
    val navigator = androidx.compose.runtime.remember(nav) { Navigator(nav, container) }
    val entry by nav.currentBackStackEntryAsState()
    val current = Tab.entries.firstOrNull { it.route == entry?.destination?.route }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavHost(
            navController = nav,
            startDestination = Tab.Today.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Tab.Today.route) { TodayScreen(navigator) }
            composable(Tab.Search.route) { SearchScreen(navigator) }
            composable(Tab.Shelf.route) { ShelfScreen(navigator) }
            composable(Tab.Calendar.route) { CalendarScreen(navigator) }
            composable(Tab.You.route) { YouScreen(navigator) }
            composable(
                Routes.DETAILS,
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType },
                    navArgument("id") { type = NavType.StringType },
                    navArgument("play") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { e ->
                DetailsScreen(
                    type = e.arguments?.getString("type").orEmpty(),
                    id = e.arguments?.getString("id").orEmpty(),
                    playVideoId = e.arguments?.getString("play"),
                    navigator = navigator,
                )
            }
        }
        if (current != null && current != Tab.Search) {
            InkDock(
                current = current,
                onSelect = { if (it == Tab.Search) navigator.openSearch() else navigator.goTab(it) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
    }
}
