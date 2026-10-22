package com.liuying.app.domain

/** "just now", "5 min ago", "yesterday": the relative last-sync time (L032). Pure. */
object RelativeTime {
    fun ago(thenMs: Long, nowMs: Long): String {
        val s = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
        return when {
            s < 45 -> "just now"
            s < 90 -> "1 minute ago"
            s < 45 * 60 -> "${(s + 30) / 60} minutes ago"
            s < 90 * 60 -> "1 hour ago"
            s < 22 * 3600 -> "${(s + 1800) / 3600} hours ago"
            s < 36 * 3600 -> "yesterday"
            s < 26L * 86400 -> "${(s + 43200) / 86400} days ago"
            else -> "over a month ago"
        }
    }
}

/** Text lines for the sync status panel. [lastSyncMs] is null until a round finished in this session. */
object SyncPanelText {
    data class Lines(val headline: String, val detail: String, val problem: Boolean)

    fun lines(
        syncing: Boolean, failure: String?, lastSyncMs: Long?, nowMs: Long, pendingChanges: Boolean, dirtySettings: Int,
    ): Lines {
        val waiting = buildList {
            if (pendingChanges) add("Changes are waiting to upload.")
            if (dirtySettings > 0) add("$dirtySettings setting${if (dirtySettings == 1) "" else "s"} not yet uploaded.")
        }.joinToString(" ")
        return when {
            syncing -> Lines("Syncing now", waiting.ifBlank { "Checking your account for changes." }, false)
            failure != null -> Lines("Last sync failed", listOf(failure, waiting).filter { it.isNotBlank() }.joinToString(" "), true)
            lastSyncMs != null -> Lines("Synced " + RelativeTime.ago(lastSyncMs, nowMs), waiting.ifBlank { "Everything is up to date." }, false)
            else -> Lines("Not synced yet this session", waiting.ifBlank { "Tap Sync now to check your account." }, false)
        }
    }
}
