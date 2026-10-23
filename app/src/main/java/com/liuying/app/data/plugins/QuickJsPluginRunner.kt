package com.liuying.app.data.plugins

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.liuying.app.domain.plugins.PluginContract
import com.liuying.app.domain.plugins.PluginInfo
import com.liuying.app.domain.plugins.PluginLimits
import com.liuying.app.domain.plugins.PluginRunException
import com.liuying.app.domain.plugins.PluginRunGuard
import com.liuying.app.domain.plugins.PluginRunRequest
import com.liuying.app.domain.plugins.PluginRunner
import com.liuying.app.domain.plugins.PluginStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a plugin script in a fresh QuickJS context per run. The context has no Android API, no files and no way to reach
 * app state: its only doors are the five host functions bound below (fetch, log, sleep, resolve, reject), and they are
 * wrapped by [PluginPrelude]. Memory and stack are capped by the engine; wall time by [PluginRunGuard].
 */
class QuickJsPluginRunner(
    private val http: PluginHttp,
    private val loadScript: suspend (PluginInfo) -> String,
    private val timeoutMs: Long = PluginLimits.RUN_TIMEOUT_MS,
) : PluginRunner {
    override val runnable: Boolean = true

    override suspend fun run(plugin: PluginInfo, request: PluginRunRequest): Result<List<PluginStream>> =
        PluginRunGuard.run(timeoutMs) { execute(plugin, request) }

    private suspend fun execute(plugin: PluginInfo, request: PluginRunRequest): List<PluginStream> {
        val script = loadScript(plugin)
        val session = http.newSession()
        val executor = Executors.newSingleThreadExecutor { r -> Thread(null, r, "liuying-plugin", 4L * 1024 * 1024).apply { isDaemon = true } }
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val outcome = Outcome()
        val answered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val hostCalls = AtomicInteger() // fetches and timers the engine is waiting on
        val js = try { QuickJs.create(dispatcher) } catch (e: Throwable) {
            executor.shutdown()
            throw PluginRunException("The script engine could not start: ${e.message ?: e.javaClass.simpleName}")
        }
        val job = scope.async {
            js.memoryLimit = PluginLimits.MEMORY_BYTES
            js.maxStackSize = PluginLimits.STACK_BYTES
            val logs = AtomicInteger()
            js.function<Any?>("__hostLog") { _ -> logs.incrementAndGet(); null } // logs are dropped on purpose: nothing leaves the sandbox
            js.function<Any?>("__hostResolve") { a ->
                if (outcome.resolved == null && outcome.rejected == null) {
                    val raw = a.firstOrNull() as? String
                    if (raw == null) outcome.rejected = "The plugin's answer could not be read."
                    else if (raw.length > 4_000_000) outcome.rejected = "The plugin's answer is larger than 4 MB."
                    else outcome.resolved = raw
                }
                answered.complete(Unit); null
            }
            js.function<Any?>("__hostReject") { a ->
                if (outcome.resolved == null && outcome.rejected == null) outcome.rejected = (a.firstOrNull() as? String ?: "The plugin failed.").take(300)
                answered.complete(Unit); null
            }
            js.function<Any?>("__hostModule") { a -> PluginModules.source(a.firstOrNull() as? String) }
            // After the plugin has answered, nothing it left behind (a request in flight, a timeout race) may hold the run open or start new work.
            val lateReply = "{\"error\":\"The plugin already answered.\"}"
            js.asyncFunction<String>("__hostFetch") { a ->
                if (answered.isCompleted) lateReply
                else try { hostCalls.incrementAndGet(); kotlinx.coroutines.coroutineScope {
                    val call = async { session.fetchJson(a.firstOrNull() as? String ?: "") }
                    val stop = async { answered.await(); lateReply }
                    try { kotlinx.coroutines.selects.select<String> { call.onAwait { it }; stop.onAwait { it } } } finally { call.cancel(); stop.cancel() }
                } } finally { hostCalls.decrementAndGet() }
            }
            js.asyncFunction<Any?>("__hostSleep") { a ->
                var left = ((a.firstOrNull() as? Number)?.toLong() ?: 0L).coerceIn(0L, PluginLimits.MAX_TIMER_MS)
                hostCalls.incrementAndGet()
                try { while (left > 0 && !answered.isCompleted) { val d = minOf(left, 25L); delay(d); left -= d } } finally { hostCalls.decrementAndGet() }
                null
            }
            try {
                js.evaluate<Any?>(PluginPrelude.SOURCE, "prelude.js", false)
                js.evaluate<Any?>(PluginModules.escapeAstral(script), "plugin.js", false)
                js.evaluate<Any?>(PluginContract.invocation(request), "invoke.js", false)
            } catch (e: QuickJsException) {
                if (outcome.resolved != null || outcome.rejected != null) return@async // the plugin already answered; ignore what happened afterwards
                throw PluginRunException("Script error: ${(e.message ?: "unknown").lineSequence().firstOrNull().orEmpty().take(300)}")
            }
        }
        var finished = false
        try {
            job.await()
            // evaluate() returning is not proof that the plugin is done: promise jobs and host calls can still be running in the engine
            // (which keeps turning on its own). Wait for the answer while it has requests or timers outstanding; once it has none and
            // stays quiet for a moment, it will never answer.
            var quietMs = 0
            while (!answered.isCompleted) {
                if (hostCalls.get() == 0) { if (quietMs >= 150) break; quietMs += 10 } else quietMs = 0
                delay(10)
            }
            if (!answered.isCompleted) outcome.diagnosis = scope.async { runCatching { js.evaluate<String?>("__liuyingDiag()", "diag.js", false) }.getOrNull() }.await()
            finished = true
        } finally {
            job.cancel()
            val close = { runCatching { js.close() }; runCatching { executor.shutdownNow() }; Unit }
            if (finished || job.isCompleted) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) { close() }
            // A timed-out or cancelled run may be stuck inside the engine, and closing waits for it: never on the caller.
            else Thread({ close() }, "liuying-plugin-close").apply { isDaemon = true }.start()
        }
        outcome.rejected?.let { throw PluginRunException(it) }
        val raw = outcome.resolved ?: throw PluginRunException(outcome.diagnosis ?: "The plugin finished without returning a result.")
        return PluginContract.parseResult(raw, plugin.name).getOrThrow()
    }

    private class Outcome {
        @Volatile var resolved: String? = null
        @Volatile var rejected: String? = null
        @Volatile var diagnosis: String? = null
    }
}
