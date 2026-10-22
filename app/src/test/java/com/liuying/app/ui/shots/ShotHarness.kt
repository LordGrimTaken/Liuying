package com.liuying.app.ui.shots

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.liuying.app.AppContainer
import com.liuying.app.ui.common.LocalContainer
import com.liuying.app.ui.common.LocalReduceMotion
import com.liuying.app.ui.nav.LiuyingApp
import com.liuying.app.ui.theme.LiuyingTheme
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.robolectric.RuntimeEnvironment
import java.io.File

/** Fake addon served from MockWebServer: one movie and one series catalog, meta with episodes, one stream. */
class FakeAddon : Dispatcher() {
    @Volatile var offline = false
    @Volatile var catalogSize = 12
    @Volatile var hugeTitles = false
    val hits = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun item(i: Int, type: String): String {
        val name = if (hugeTitles && i % 3 == 0) "A Very Long Title That Goes On And On Forever: The Complete Chronicles of the Lost Kingdom Part $i"
        else if (i % 5 == 1) "龙之纪元：暗影王子 $i" else "Title $i"
        val prefix = if (type == "movie") "tt" else "ttS"
        return """{"id":"$prefix$i","type":"$type","name":"$name","releaseInfo":"20${10 + i % 15}","imdbRating":"7.${i % 10}","genres":["Drama","Action"],"description":"A synopsis for $name that is long enough to wrap over a few lines on a narrow phone screen so that clamping can be seen."}"""
    }

    private fun catalog(type: String) = """{"metas":[${(1..catalogSize).joinToString(",") { item(it, type) }}]}"""

    private fun meta(id: String): String {
        val series = id.startsWith("ttS")
        val vids = if (series) (1..3).flatMap { s -> (1..6).map { e ->
            val rel = if (s == 3 && e > 3) "2099-01-0$e" else "2020-0$s-0$e"
            """{"id":"$id:$s:$e","season":$s,"episode":$e,"title":"Episode $e of season $s","released":"${rel}T00:00:00.000Z","overview":"What happens in episode $e."}"""
        } }.joinToString(",") else ""
        return """{"meta":{"id":"$id","type":"${if (series) "series" else "movie"}","name":"Title ${id.drop(2)}","releaseInfo":"2021","runtime":"122 min","imdbRating":"8.1","genres":["Drama","Action"],"cast":["A B","C D"],"director":["E F"],"description":"A synopsis that is long enough to wrap over several lines on a narrow phone screen, so that the clamp and the tap to expand behaviour can be seen in the screenshot. ${"More text. ".repeat(20)}","videos":[$vids]}}"""
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val p = request.path.orEmpty()
        hits += p
        if (offline) return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START)
        val json = { b: String -> MockResponse().setHeader("Content-Type", "application/json").setHeader("Cache-Control", "no-store").setBody(b) }
        return when {
            p.endsWith("/manifest.json") -> json("""{"id":"test.addon","name":"Test Addon","version":"1.0.0","description":"A fake addon for screenshots","resources":["catalog","meta","stream"],"types":["movie","series"],"idPrefixes":["tt"],"catalogs":[{"type":"movie","id":"top","name":"Popular Movies","extra":[{"name":"genre","options":["Drama","Action","Comedy"]},{"name":"skip"},{"name":"search"}]},{"type":"series","id":"top","name":"Popular Series","extra":[{"name":"skip"},{"name":"search"}]},{"type":"movie","id":"new","name":"New Movies","extra":[{"name":"skip"}]}]}""")
            p.contains("/catalog/movie/") -> json(catalog("movie"))
            p.contains("/catalog/series/") -> json(catalog("series"))
            p.contains("/meta/") -> json(meta(p.substringAfter("/meta/").substringAfter("/").substringBefore(".json").substringBefore("%3A")))
            p.contains("/stream/") -> json("""{"streams":[{"name":"Test 1080p","description":"1080p WEB-DL x265 HDR\n2.1 GB","url":"http://127.0.0.1:1/v.mp4"},{"name":"Test 720p","title":"720p x264","url":"http://127.0.0.1:1/v2.mp4"}]}""")
            else -> MockResponse().setResponseCode(404)
        }
    }
}

abstract class ShotBase {
    @get:Rule val rule = createComposeRule()
    val addon = FakeAddon()
    lateinit var server: MockWebServer
    lateinit var container: AppContainer

    @Before fun boot() {
        server = MockWebServer().also { it.dispatcher = addon; it.start() }
        container = AppContainer(ApplicationProvider.getApplicationContext<Application>())
    }

    @After fun down() { runCatching { server.shutdown() } }

    fun seedSource() = runBlocking {
        val p = container.sources.preview(server.url("/manifest.json").toString()).getOrThrow()
        container.sources.install(p, confirmRisky = true).getOrThrow()
    }

    fun app(fontScale: Float = 1.0f, accent: String = "vermilion", textSize: String = "m") {
        RuntimeEnvironment.setFontScale(fontScale)
        rule.setContent {
            LiuyingTheme(accent, textSize) {
                CompositionLocalProvider(LocalContainer provides container, LocalReduceMotion provides true) { LiuyingApp() }
            }
        }
    }

    fun waitFor(timeoutMs: Long = 8000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            rule.waitForIdle()
            if (cond()) return
            Thread.sleep(50)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
    }

    fun shot(name: String) {
        rule.waitForIdle()
        File("build/shots").mkdirs()
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }
}
