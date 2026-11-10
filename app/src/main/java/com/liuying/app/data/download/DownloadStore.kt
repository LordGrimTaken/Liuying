package com.liuying.app.data.download

import com.liuying.app.domain.DownloadItem
import com.liuying.app.domain.DownloadState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The files behind the download queue (L122): one directory per title, a `.part` file while a transfer runs, and
 * a single `index.json` at the root so a half-finished queue survives the process being killed.
 *
 * Pure file I/O with no Android API, so it can be tested on the JVM against a temporary directory.
 * The decisions about *what* to download live in [com.liuying.app.domain.DownloadLogic].
 */
class DownloadStore(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val indexFile get() = File(root, "index.json")

    /** Everything known about previous downloads; an unreadable index means "nothing", never a crash. */
    fun load(): List<DownloadItem> {
        val f = indexFile
        if (!f.exists()) return emptyList()
        return try {
            val parsed = json.decodeFromString(IndexFile.serializer(), f.readText())
            parsed.items.map { it.toItem() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(items: List<DownloadItem>) {
        root.mkdirs()
        val data = IndexFile(SCHEMA, items.map { Entry.from(it) })
        indexFile.writeText(json.encodeToString(IndexFile.serializer(), data))
    }

    /** The file being written; its name never changes during the transfer, so a resume appends to it. */
    fun partFile(item: DownloadItem, ext: String): File = ensureTitleDir(item).let { File(it, "${fileBase(item)}.$ext.part") }

    /** Where the file lives once complete. */
    fun dataFile(item: DownloadItem, ext: String): File = ensureTitleDir(item).let { File(it, "${fileBase(item)}.$ext") }

    /**
     * Marks a finished transfer: the `.part` file is renamed and the byte count is read from the file itself, so a
     * server that lied about the size cannot leave a truncated file looking complete.
     */
    fun complete(item: DownloadItem, ext: String): DownloadItem {
        val part = partFile(item, ext)
        val final = dataFile(item, ext)
        val size = if (part.exists()) part.length() else item.downloadedBytes
        runCatching { if (final.exists()) final.delete() }
        runCatching { part.renameTo(final) }
        return item.copy(state = DownloadState.Done, downloadedBytes = size, sizeBytes = item.sizeBytes ?: size, error = null)
    }

    /** Deletes the bytes and, when it was the last one, the title directory. */
    fun delete(item: DownloadItem): Boolean {
        val dir = titleDir(item)
        var removed = false
        dir.listFiles()?.forEach { f -> if (f.name.startsWith(fileBase(item) + ".")) removed = f.delete() || removed }
        runCatching { if (dir.listFiles()?.isEmpty() == true) dir.delete() }
        return removed
    }

    /** Bytes free where downloads live, for the pre-flight check. */
    fun freeBytes(): Long = root.usableSpace

    /** Whatever is on disk for an item, complete or partial; null when nothing was written yet. */
    fun bytesOnDisk(item: DownloadItem): Long? {
        val files = titleDir(item).listFiles()?.filter { it.name.startsWith(fileBase(item) + ".") }.orEmpty()
        return files.maxOfOrNull { it.length() }?.takeIf { files.isNotEmpty() }
    }

    /** The directory for an item, without touching the disk: a path helper must not recreate what was deleted. */
    private fun titleDir(item: DownloadItem): File {
        val (type, titleId) = item.key.split("|").let { (it.getOrNull(0) ?: "other") to (it.getOrNull(1) ?: "unknown") }
        return File(File(root, type), safeName(titleId))
    }

    private fun ensureTitleDir(item: DownloadItem): File = titleDir(item).apply { mkdirs() }

    /**
     * The file name is the video id, not the whole key: the directory already names the type and title, and an
     * episode id like "ttS1:1:2" or a movie id like "tt123" is unique inside it.
     */
    private fun fileBase(item: DownloadItem): String = safeName(item.key.split("|").last())

    /** A title id from a source can contain anything; keep it inside the directory it belongs to. */
    private fun safeName(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(96).ifBlank { "item" }

    @Serializable
    private data class IndexFile(val schema: Int = SCHEMA, val items: List<Entry> = emptyList())

    @Serializable
    private data class Entry(
        val key: String,
        val title: String,
        val sizeBytes: Long? = null,
        val downloadedBytes: Long = 0,
        val state: String = "Queued",
        val queuedAt: Long = 0,
        val error: String? = null,
        @SerialName("v") val version: Int = 1,
    ) {
        fun toItem() = DownloadItem(
            key = key, title = title, sizeBytes = sizeBytes, state = runCatching { DownloadState.valueOf(state) }.getOrDefault(DownloadState.Queued),
            downloadedBytes = downloadedBytes, queuedAt = queuedAt, error = error,
        )

        companion object {
            fun from(i: DownloadItem) = Entry(i.key, i.title, i.sizeBytes, i.downloadedBytes, i.state.name, i.queuedAt, i.error)
        }
    }

    private companion object {
        const val SCHEMA = 1
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}

/** Extension for the file name: the URL's own extension when it has a sane one, else the parser's filename. */
fun downloadExtension(url: String?, filename: String? = null): String {
    fun extOf(s: String?): String? = s?.substringBefore('?')?.substringBefore('#')?.let { path ->
        path.substringAfterLast('.', "").lowercase().takeIf { it in setOf("mkv", "mp4", "avi", "m4v", "ts", "webm", "mov") }
    }
    return extOf(filename) ?: extOf(url) ?: "mkv"
}
