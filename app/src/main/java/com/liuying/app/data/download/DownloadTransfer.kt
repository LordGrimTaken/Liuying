package com.liuying.app.data.download

import com.liuying.app.domain.DownloadItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * One transfer of a direct http(s) file into a `.part` file (L122).
 *
 * Resumes with a `Range` request when bytes are already on disk. If the server ignores the range and starts from
 * zero, the partial file is truncated rather than appended to, because appending would corrupt the result.
 * No Android APIs: tested against a local HTTP server.
 */
class DownloadTransfer(
    private val client: OkHttpClient,
    private val store: DownloadStore,
) {
    /**
     * Downloads [item] from [url] with the extension [ext], reporting cumulative bytes through [onProgress].
     *
     * @return the item, marked done, with the byte count read back from the file.
     * @throws DownloadTransferException when the server refuses the request or the connection fails.
     */
    suspend fun run(
        item: DownloadItem,
        url: String,
        ext: String,
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): DownloadItem = withContext(Dispatchers.IO) {
        val part = store.partFile(item, ext)
        part.parentFile?.mkdirs()
        var have = if (part.exists()) part.length() else 0L

        val request = Request.Builder()
            .url(url)
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DownloadTransferException("The server answered ${response.code}.")
                }
                val resumed = response.code == 206 && have > 0
                if (!resumed) {
                    // Either a fresh download, or the server ignored the range: start the file over.
                    have = 0
                    part.delete()
                }
                val body = response.body ?: throw DownloadTransferException("The server sent an empty response.")
                val remaining = body.contentLength().takeIf { it >= 0 }
                val total = if (remaining != null) have + remaining else item.sizeBytes
                val buffer = ByteArray(64 * 1024)
                // Appending on a resume is essential: outputStream() would truncate the bytes already on disk and
                // leave a file whose head is missing. A fresh start opens it empty.
                java.io.FileOutputStream(part, /* append = */ resumed).use { out ->
                    body.byteStream().use { input ->
                        var read: Int
                        while (true) {
                            read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            have += read
                            onProgress(have, total)
                        }
                    }
                }
                if (total != null && have != total) {
                    throw DownloadTransferException("The transfer stopped at $have of $total bytes.")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadTransferException) {
            throw e
        } catch (e: IOException) {
            throw DownloadTransferException("The connection failed: ${e.message ?: "unknown error"}")
        }
        store.complete(item.copy(downloadedBytes = have, sizeBytes = item.sizeBytes ?: have), ext)
    }
}

class DownloadTransferException(message: String) : Exception(message)

/** Where a downloaded file for an item would live, for the player to prefer local playback. */
fun DownloadStore.localFileFor(item: DownloadItem, ext: String = "mkv"): File? =
    dataFile(item, ext).takeIf { it.exists() }
