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
        val js = try { QuickJs.create(dispatcher) } catch (e: Throwable) {
            executor.shutdown()
            throw PluginRunException("The script engine could not start: ${e.message ?: e.javaClass.simpleName}")
        }
        val job = scope.async {
            js.memoryLimit = PluginLimits.MEMORY_BYTES
            js.maxStackSize = PluginLimits.STACK_BYTES
            val logs = AtomicInteger()
            js.function<Any?>("__hostLog") { _ -> logs.incrementAndGet(); null } // logs are dropped on purpose: nothing leaves the sandbox
            js.function<Any?>("__hostResolve") { a -> outcome.resolved = (a.firstOrNull() as? String)?.takeIf { it.length <= 4_000_000 }; null }
            js.function<Any?>("__hostReject") { a -> outcome.rejected = (a.firstOrNull() as? String ?: "The plugin failed.").take(300); null }
            js.asyncFunction<String>("__hostFetch") { a -> session.fetchJson(a.firstOrNull() as? String ?: "") }
            js.asyncFunction<Any?>("__hostSleep") { a ->
                delay(((a.firstOrNull() as? Number)?.toLong() ?: 0L).coerceIn(0L, PluginLimits.MAX_TIMER_MS)); null
            }
            try {
                js.evaluate<Any?>(PluginPrelude.SOURCE, "prelude.js", false)
                js.evaluate<Any?>(script, "plugin.js", false)
                js.evaluate<Any?>(PluginContract.invocation(request), "invoke.js", false)
            } catch (e: QuickJsException) {
                throw PluginRunException("Script error: ${(e.message ?: "unknown").lineSequence().firstOrNull().orEmpty().take(300)}")
            }
        }
        var finished = false
        try {
            job.await()
            finished = true
        } finally {
            job.cancel()
            val close = { runCatching { js.close() }; runCatching { executor.shutdownNow() }; Unit }
            if (finished || job.isCompleted) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) { close() }
            // A timed-out or cancelled run may be stuck inside the engine, and closing waits for it: never on the caller.
            else Thread({ close() }, "liuying-plugin-close").apply { isDaemon = true }.start()
        }
        outcome.rejected?.let { throw PluginRunException(it) }
        val raw = outcome.resolved ?: throw PluginRunException("The plugin finished without returning a result.")
        return PluginContract.parseResult(raw, plugin.name).getOrThrow()
    }

    private class Outcome {
        @Volatile var resolved: String? = null
        @Volatile var rejected: String? = null
    }
}
