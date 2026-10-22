package com.liuying.app.ui.nav

/** The five bottom-nav destinations. Search and Calendar live in the top bar ([Overlay]). */
enum class Tab(val route: String, val label: String) {
    Today("today", "Today"),
    Movies("movies", "Movies"),
    Series("series", "Series"),
    Shelf("shelf", "Shelf"),
    You("you", "You"),
}

/** Routes reached from the top-bar icons rather than the bottom nav. */
object Overlay {
    const val SEARCH = "search"
    const val CALENDAR = "calendar"
}
