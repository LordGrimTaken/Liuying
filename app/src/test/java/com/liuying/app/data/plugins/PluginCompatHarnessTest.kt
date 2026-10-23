package com.liuying.app.data.plugins

import com.liuying.app.domain.plugins.PluginInfo
import com.liuying.app.domain.plugins.PluginRunRequest
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Developer harness, skipped unless LIUYING_PLUGIN_DIR points at a folder of downloaded plugin scripts (<id>.js).
 * Third-party scripts are never committed. Nothing here touches the network: every `fetch` is answered by a fake.
 *   LIUYING_PLUGIN_DIR=/path/to/pl LIUYING_PLUGIN_REPORT=/path/report.tsv ./gradlew :app:testDebugUnitTest --tests '*PluginCompatHarnessTest*'
 */
class PluginCompatHarnessTest {
    private val dir: File? = System.getenv("LIUYING_PLUGIN_DIR")?.let(::File)?.takeIf { it.isDirectory }
    private val report: File? = System.getenv("LIUYING_PLUGIN_REPORT")?.let(::File)

    private fun resp(req: Request, code: Int, body: String, type: String = "application/json") =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message(if (code == 200) "OK" else "ERR").body(body.toResponseBody(type.toMediaType())).build()

    private fun runner(script: String, timeoutMs: Long = 10_000, answer: (Request) -> Response): QuickJsPluginRunner {
        val client = OkHttpClient.Builder().followRedirects(false).addInterceptor(Interceptor { c -> answer(c.request()) }).build()
        return QuickJsPluginRunner(PluginHttp("Liuying/test (Android)", client), loadScript = { script }, timeoutMs = timeoutMs)
    }

    private val info = PluginInfo("p", "P", "1", "", "https://repo.example.com/p.js", emptyList(), emptyList())
    private fun run(r: QuickJsPluginRunner, req: PluginRunRequest = PluginRunRequest("603", "movie")) = runBlocking { r.run(info, req) }

    @Test fun vidlinkNeverEndsWithoutAResult() {
        val d = dir ?: return assumeTrue("LIUYING_PLUGIN_DIR not set", false)
        val script = File(d, "vidlink.js").readText()
        val m3u8 = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1920x1080\n1080/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1280x720\nhttps://cdn.example.com/720.m3u8\n"
        val scenarios: Map<String, (Request) -> Response> = mapOf(
            "instant-json-no-playlist" to { r -> resp(r, 200, """{"result":"abc","title":"T","release_date":"2020-01-01","runtime":100}""") },
            "delayed-json" to { r -> Thread.sleep(80 + (r.url.toString().hashCode() and 0xff).toLong()); resp(r, 200, """{"result":"abc","title":"T"}""") },
            "http-500-html" to { r -> resp(r, 500, "<html>oops</html>", "text/html") },
            "non-json-200" to { r -> resp(r, 200, "<html>not json</html>", "text/html") },
            "empty-body" to { r -> resp(r, 200, "") },
            "io-error" to { r -> throw IOException("boom") },
            "full-success" to { r ->
                val u = r.url.toString()
                when {
                    u.contains("m3u8") || u.endsWith("playlist.m3u8") -> resp(r, 200, m3u8, "application/vnd.apple.mpegurl")
                    u.contains("/api/b/") -> resp(r, 200, """{"stream":{"playlist":"https://cdn.example.com/playlist.m3u8"}}""")
                    else -> resp(r, 200, """{"result":"abc","title":"T","name":"T","release_date":"2020-01-01","runtime":100}""")
                }
            },
        )
        val sb = StringBuilder()
        for ((n, a) in scenarios) for (req in listOf(PluginRunRequest("603", "movie"), PluginRunRequest("1396", "tv", 1, 2))) {
            val t0 = System.currentTimeMillis()
            val r = run(runner(script, answer = a), req)
            sb.append("vidlink/$n/${req.mediaType}: ").append(r.fold({ "OK ${it.size} streams" }, { "FAIL ${it.message}" })).append(" (${System.currentTimeMillis() - t0}ms)\n")
        }
        println(sb); report?.appendText(sb.toString())
        check(!sb.contains("without returning")) { sb.toString() }
    }

    @Test fun sweepEveryScript() {
        val d = dir ?: return assumeTrue("LIUYING_PLUGIN_DIR not set", false)
        val body = """{"results":[],"data":[],"items":[],"streams":[],"success":true,"status":"ok","result":""}"""
        val sb = StringBuilder()
        for (f in d.listFiles { x -> x.name.endsWith(".js") }!!.sortedBy { it.name }) {
            val t0 = System.currentTimeMillis()
            val r = run(runner(f.readText(), timeoutMs = 15_000) { resp(it, 200, body) }, PluginRunRequest("603", "movie"))
            sb.append(f.nameWithoutExtension).append('\t').append(r.fold({ "OK ${it.size}" }, { "FAIL ${it.message?.replace('\n', ' ')}" })).append('\t').append(System.currentTimeMillis() - t0).append("ms\n")
        }
        println(sb); File(report?.path ?: "/dev/null").takeIf { report != null }?.let { it.writeText(sb.toString()) }
    }

    @Test fun experiments() {
        assumeTrue("LIUYING_PLUGIN_DIR not set", dir != null)
        val html: (Request) -> Response = { r -> resp(r, 200, "<html>x</html>", "text/html") }
        val scripts = linkedMapOf(
            "A-async-try-json" to "module.exports={getStreams: async function(){ try { const r = await fetch('https://a.example.com/x'); await r.json(); } catch(e){ return []; } return []; }};",
            "B-generator-driver" to """
              var __async=(t,a,g)=>new Promise((res,rej)=>{var ok=v=>{try{st(g.next(v))}catch(e){rej(e)}},er=v=>{try{st(g.throw(v))}catch(e){rej(e)}},st=x=>x.done?res(x.value):Promise.resolve(x.value).then(ok,er);st((g=g.apply(t,a)).next())});
              function getStreams(){return __async(this,null,function*(){try{const r=yield fetch('https://a.example.com/x');const j=yield r.json();return [j]}catch(e){return []}})}
              module.exports={getStreams:getStreams};""",
            "C-text-only" to "module.exports={getStreams: async function(){ const r = await fetch('https://a.example.com/x'); const t = await r.text(); return []; }};",
            "D-emoji" to """module.exports={getStreams: async function(){ return [{name:'a\u{1F3AC} \u4e2d\u6587 x', title:'\u{1F3AC}\ud83c\udfac', url:'https://c.example.com/x'}]; }};""",
            "E-emoji-literal" to "module.exports={getStreams: function(){ return [{name:'🎬 中文', url:'https://c.example.com/x'}]; }};",
        )
        val sb = StringBuilder()
        for ((n, sc) in scripts) sb.append("exp/$n: ").append(run(runner(sc, answer = html)).fold({ "OK ${it.size} " + it.firstOrNull()?.name }, { "FAIL ${it.message}" })).append('\n')
        println(sb); report?.appendText(sb.toString())
    }
}
