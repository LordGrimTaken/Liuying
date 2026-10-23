package com.liuying.app.data.plugins

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Probe, only runs with LIUYING_PROBE=1: documents when quickjs-kt's evaluate() returns relative to promise jobs and async host calls. */
class QuickJsSemanticsProbeTest {
    @Test fun probe() = runBlocking {
        assumeTrue(System.getenv("LIUYING_PROBE") == "1")
        val log = StringBuilder()
        fun t(n: String, body: suspend (QuickJs, MutableList<String>) -> Unit) = runBlocking {
            val js = QuickJs.create(Dispatchers.Default); val seen = java.util.Collections.synchronizedList(ArrayList<String>())
            js.function<Any?>("mark") { a -> seen += a[0].toString(); null }
            js.asyncFunction<String>("slow") { a -> delay(150); "v" + a[0] }
            try { body(js, seen) } catch (e: Throwable) { seen += "EXC " + e.message?.take(80) }
            log.append("$n: ").append(seen).append('\n'); runCatching { js.close() }
        }
        t("microtask-after-evaluate") { js, seen -> js.evaluate<Any?>("Promise.resolve().then(()=>mark('micro'))", "a.js", false); seen += "returned" ; delay(50); seen += "after50ms" }
        t("async-then-after-evaluate") { js, seen -> js.evaluate<Any?>("slow(1).then(v=>mark('slow '+v))", "a.js", false); seen += "returned"; delay(400); seen += "after400ms" }
        t("async-chain") { js, seen -> js.evaluate<Any?>("slow(1).then(v=>slow(2)).then(v=>mark('chain '+v))", "a.js", false); seen += "returned"; delay(600); seen += "after600ms" }
        t("never-resolving") { js, seen -> js.evaluate<Any?>("new Promise(function(){}).then(()=>mark('x'))", "a.js", false); seen += "returned" }
        t("tick-pumps") { js, seen -> js.evaluate<Any?>("slow(1).then(v=>mark('slow '+v))", "a.js", false); seen += "returned"; repeat(5) { js.evaluate<Any?>("0", "t.js", false); delay(60) }; seen += "ticked" }
        t("sleep-loop-no-eval") { js, seen -> js.evaluate<Any?>("slow(1).then(v=>mark('slow '+v))", "a.js", false); seen += "returned"; delay(300); seen += "300ms-noeval" }
        println("PROBE\n$log")
        java.io.File(System.getenv("LIUYING_PLUGIN_REPORT") ?: "/dev/null").appendText("PROBE\n$log")
    }
}
