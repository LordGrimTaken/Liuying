package com.liuying.app.data.repo

import com.liuying.app.domain.model.MetaPreview
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * L088: the last titles each Today row showed, kept as small files so the next start paints them at once
 * while fresh data loads. Files live in the cache directory, so "Clear cache" drops them.
 */
class RowSnapshots(private val dir: File, private val nowMs: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val ser = ListSerializer(MetaPreview.serializer())

    private fun file(key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$h.json")
    }

    fun get(key: String, maxAgeMs: Long = 14L * 24 * 3600 * 1000): List<MetaPreview>? = runCatching {
        val f = file(key)
        if (!f.isFile || nowMs() - f.lastModified() > maxAgeMs) return null
        json.decodeFromString(ser, f.readText()).takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun put(key: String, items: List<MetaPreview>) {
        if (items.isEmpty()) return
        runCatching {
            dir.mkdirs()
            val f = file(key)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(json.encodeToString(ser, items))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
}
