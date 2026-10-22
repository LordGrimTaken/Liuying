package com.liuying.app.data.settings

import android.content.SharedPreferences
import com.liuying.app.ui.player.TrackMemoryStore
import com.liuying.app.ui.player.logic.TrackChoice

/**
 * Audio and subtitle language remembered per series across sessions (L047). Kept in its own small preferences file,
 * newest 300 entries; each value is "<timestamp>;<encoded choice>".
 */
class TrackMemoryPrefs(private val prefs: SharedPreferences, private val now: () -> Long = System::currentTimeMillis) : TrackMemoryStore {
    override fun get(key: String): TrackChoice? = runCatching {
        prefs.getString(key, null)?.substringAfter(';', "")?.let { TrackChoice.decode(it) }
    }.getOrNull()

    override fun put(key: String, choice: TrackChoice) {
        runCatching {
            val ed = prefs.edit()
            ed.putString(key, "${now()};${choice.encode()}")
            val all = prefs.all
            if (all.size >= MAX) {
                all.entries.sortedBy { (it.value as? String)?.substringBefore(';')?.toLongOrNull() ?: 0L }
                    .take(all.size - MAX + 1).forEach { ed.remove(it.key) }
            }
            ed.apply()
        }
    }

    private companion object { const val MAX = 300 }
}
