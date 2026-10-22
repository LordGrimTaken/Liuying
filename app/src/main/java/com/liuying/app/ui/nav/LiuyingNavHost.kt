package com.liuying.app.ui.nav

import androidx.compose.foundation.background
import com.liuying.app.ui.common.LocalReduceMotion
import com.liuying.app.ui.common.EaseOutSoft
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
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
import com.liuying.app.ui.calendar.calendarBadge
import com.liuying.app.ui.details.DetailsScreen
import com.liuying.app.ui.home.TodayScreen
import com.liuying.app.ui.browse.BrowseScreen
import com.liuying.app.ui.browse.TypePageScreen
import com.liuying.app.ui.sources.SourceDetailScreen
import com.liuying.app.ui.calendar.CalendarScreen
import com.liuying.app.ui.library.ShelfScreen
import com.liuying.app.ui.search.SearchScreen
import com.liuying.app.ui.settings.YouScreen

/** Height reserved at the bottom of each screen so content clears the floating dock. */
val DockClearance = 96.dp

/** Opens the details page, remembering the list item it came from so it can render before the network answers. */
class Navigator(private val nav: NavHostController, private val container: AppContainer) {
    fun openDetails(preview: MetaPreview, fallbackType: String, sourceId: Long?) {
        val type = preview.type.ifBlank { fallbackType }
        container.putHint(type, preview.copy(type = type), sourceId)
        nav.navigate(Routes.details(type, preview.id))
    }

    /** Opens details and goes straight to the stream list for the title's main play target. */
    fun playTitle(preview: MetaPreview, fallbackType: String, sourceId: Long?) {
        val type = preview.type.ifBlank { fallbackType }
        container.putHint(type, preview.copy(type = type), sourceId)
        nav.navigate(Routes.details(type, preview.id, Routes.PLAY_PRIMARY))
    }

    fun openSource(id: Long) = nav.navigate(Routes.source(id))
    fun openBrowse(catalogKey: String, genre: String? = null) = nav.navigate(Routes.browse(catalogKey, genre))
    fun openHistory() = nav.navigate(Overlay.HISTORY) { launchSingleTop = true }
    fun openCalendar() = nav.navigate(Overlay.CALENDAR) { launchSingleTop = true }
    fun openTitle(type: String, id: String, play: String? = null) = nav.navigate(Routes.details(type, id, play))
    fun back() { nav.popBackStack() }
    /** The full-screen search overlay: it covers the dock and Back returns to where the user was. */
    fun openSearch() { nav.navigate(Overlay.SEARCH) { launchSingleTop = true } }
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
    val link by container.pendingLink.collectAsState()
    androidx.compose.runtime.LaunchedEffect(link) { if (link != null) navigator.goTab(Tab.You) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val current = Tab.entries.firstOrNull { it.route == route }
    val showChrome = current != null || route == Overlay.CALENDAR
    val account by container.sync.state.collectAsState()
    val calBadge by androidx.compose.runtime.remember(container) { container.calendarBadge() }.collectAsState(0)
    val chrome = androidx.compose.runtime.remember { ChromeState() }
    val reduce = LocalReduceMotion.current
    val rise = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.roundToPx() }

    androidx.compose.runtime.CompositionLocalProvider(LocalChrome provides chrome) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavHost(
            navController = nav,
            startDestination = Tab.Today.route,
            modifier = Modifier.fillMaxSize(),
            // a new page fades in and rises 10dp (320ms); only on navigation, never on background refresh
            enterTransition = { if (reduce) EnterTransition.None else fadeIn(tween(320, easing = EaseOutSoft)) + slideInVertically(tween(320, easing = EaseOutSoft)) { rise } },
            exitTransition = { if (reduce) ExitTransition.None else fadeOut(tween(160)) },
            popEnterTransition = { if (reduce) EnterTransition.None else fadeIn(tween(320, easing = EaseOutSoft)) },
            popExitTransition = { if (reduce) ExitTransition.None else fadeOut(tween(160)) },
        ) {
            composable(Tab.Today.route) { TodayScreen(navigator) }
            composable(Tab.Movies.route) { TypePageScreen("movie", navigator) }
            composable(Tab.Series.route) { TypePageScreen("series", navigator) }
            composable(Overlay.SEARCH) { SearchScreen(navigator) }
            composable(Tab.Shelf.route) { ShelfScreen(navigator) }
            composable(Overlay.CALENDAR) { CalendarScreen(navigator) }
            composable(Overlay.HISTORY) { com.liuying.app.ui.history.HistoryScreen(navigator) }
            composable(Tab.You.route) { YouScreen(navigator) }
            composable(
                Routes.SOURCE,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { e -> SourceDetailScreen(e.arguments?.getLong("id") ?: -1L, navigator) }
            composable(
                Routes.BROWSE,
                arguments = listOf(
                    navArgument("key") { type = NavType.StringType },
                    navArgument("genre") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { e -> BrowseScreen(e.arguments?.getString("key").orEmpty(), e.arguments?.getString("genre"), navigator) }
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
        if (showChrome) {
            LiuyingTopBar(
                solid = chrome.solid, account = account, calendarBadge = calBadge,
                onSearch = navigator::openSearch, onCalendar = navigator::openCalendar,
                onSettings = { navigator.goTab(Tab.You) }, onAccount = { navigator.goTab(Tab.You) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            InkDock(current = current, onSelect = { navigator.goTab(it) }, modifier = Modifier.align(Alignment.BottomCenter))
        }
        com.liuying.app.ui.onboarding.ClipboardLinkOffer(container)
    }
    }
}
