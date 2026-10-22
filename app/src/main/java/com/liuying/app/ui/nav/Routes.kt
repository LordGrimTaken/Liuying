package com.liuying.app.ui.nav

import android.net.Uri

object Routes {
    /** `play` value meaning "open streams for whatever Play would do" (next episode, resume, or the movie). */
    const val PLAY_PRIMARY = "primary"
    const val DETAILS = "details/{type}/{id}?play={play}"
    fun details(type: String, id: String, play: String? = null): String =
        "details/${Uri.encode(type)}/${Uri.encode(id)}" + (play?.let { "?play=${Uri.encode(it)}" } ?: "")
}
