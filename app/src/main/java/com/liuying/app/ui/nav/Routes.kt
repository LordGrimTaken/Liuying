package com.liuying.app.ui.nav

import android.net.Uri

object Routes {
    const val DETAILS = "details/{type}/{id}?play={play}"
    fun details(type: String, id: String, play: String? = null): String =
        "details/${Uri.encode(type)}/${Uri.encode(id)}" + (play?.let { "?play=${Uri.encode(it)}" } ?: "")
}
