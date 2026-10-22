package com.liuying.app.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class UpdateException(message: String) : IOException(message)

/** Network half of the updater: fetch the manifest, download and hash-check the APK. No Android types. */
class Updater(
    private val http: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    suspend fun fetchManifest(): UpdateManifest = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${baseUrl.trimEnd('/')}/downloads/latest.json")
            .header("Accept", "application/json").header("Cache-Control", "no-cache").build()
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw UpdateException("The update server answered with HTTP ${r.code}.")
                val body = r.body?.source()?.let { it.request(64 * 1024L); it.buffer.readUtf8() }.orEmpty()
                UpdateManifest.parse(body) ?: throw UpdateException("The update information was not valid.")
            }
        } catch (e: UpdateException) { throw e
        } catch (e: IOException) { throw UpdateException("Could not reach the update server. Check your connection.") }
    }

    /** The APK must live on the same host as the manifest, so a tampered manifest cannot point elsewhere. */
    fun apkRequest(m: UpdateManifest): Request {
        val base = baseUrl.toHttpUrlOrNull() ?: throw UpdateException("Bad update server address.")
        val apk = m.apkUrl.toHttpUrlOrNull() ?: throw UpdateException("The update link was not valid.")
        if (!apk.host.equals(base.host, ignoreCase = true) || (base.isHttps && !apk.isHttps)) {
            throw UpdateException("The update link points somewhere unexpected, so it was refused.")
        }
        return Request.Builder().url(apk).build()
    }

    /** Downloads to [dest] (via a .part file), reporting 0..1 progress; deletes the file and throws on a hash mismatch. */
    suspend fun download(m: UpdateManifest, dest: File, onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val req = apkRequest(m)
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        part.delete(); dest.delete()
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw UpdateException("The download failed (HTTP ${r.code}).")
                val body = r.body ?: throw UpdateException("The download was empty.")
                val total = body.contentLength().takeIf { it > 0 } ?: m.sizeBytes
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                var lastPct = -1
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); digest.update(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val f = (done.toFloat() / total).coerceIn(0f, 1f)
                                val pct = ProgressThrottle.percent(f)
                                if (pct != lastPct) { lastPct = pct; onProgress(f) } // QA #33: at most ~100 emissions
                            }
                        }
                    }
                }
                if (m.sizeBytes > 0 && done != m.sizeBytes) throw UpdateException("The download was incomplete. Please try again.")
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(m.sha256, ignoreCase = true)) throw UpdateException("The download did not match its checksum, so it was discarded.")
            }
            if (!part.renameTo(dest)) throw UpdateException("Could not save the download.")
            onProgress(1f)
            dest
        } catch (e: UpdateException) { part.delete(); throw e
        } catch (e: IOException) { part.delete(); throw UpdateException("The download was interrupted. Please try again.") }
    }

    /** Whole-percent bucket of a 0..1 fraction; callers emit only when it changes. */
    object ProgressThrottle { fun percent(f: Float): Int = (f.coerceIn(0f, 1f) * 100).toInt() }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.liuyingapp.com"
        fun apkFileName(m: UpdateManifest) = "Liuying-${m.versionName.filter { it.isLetterOrDigit() || it == '.' || it == '-' }}.apk"
    }
}
