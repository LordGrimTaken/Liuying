package com.liuying.app.data.repo

import com.liuying.app.data.remote.AddonClient
import com.liuying.app.data.remote.AddonFailure
import com.liuying.app.domain.model.Resources
import com.liuying.app.domain.model.Source
import com.liuying.app.domain.model.declares
import com.liuying.app.domain.model.declaresAny
import com.liuying.app.domain.model.needsInput
import com.liuying.app.domain.model.usableCatalogs

/** One line of a source test (L081). */
data class TestStep(val resource: String, val status: Status, val detail: String, val ms: Long) {
    enum class Status { Pass, Fail, Skipped }
}

object SourceTestText {
    /** "3 of 4 checks passed" */
    fun summary(steps: List<TestStep>): String {
        val ran = steps.filter { it.status != TestStep.Status.Skipped }
        if (ran.isEmpty()) return "Nothing to check: this source declares no resources the app can test."
        val ok = ran.count { it.status == TestStep.Status.Pass }
        return if (ok == ran.size) "All $ok checks passed." else "$ok of ${ran.size} checks passed."
    }
}

/**
 * Asks a source for each resource it declares, with a real request, and reports pass/fail for each. Fresh requests
 * (never the cache). Uses the first catalog's first title as the sample for meta, streams and subtitles.
 */
class SourceTester(private val client: AddonClient) {
    suspend fun run(source: Source): List<TestStep> {
        val m = source.manifest
        val base = source.baseUrl
        val out = ArrayList<TestStep>()
        var sampleType: String? = null
        var sampleId: String? = null

        suspend fun <T> timed(resource: String, block: suspend () -> Result<T>, pass: (T) -> String?): T? {
            val t0 = System.currentTimeMillis()
            val r = block()
            val ms = System.currentTimeMillis() - t0
            val v = r.getOrNull()
            if (v != null) {
                val problem = pass(v)
                out += if (problem == null) TestStep(resource, TestStep.Status.Pass, "Answered in ${ms} ms", ms) else TestStep(resource, TestStep.Status.Fail, problem, ms)
            } else {
                val e = r.exceptionOrNull()
                out += TestStep(resource, TestStep.Status.Fail, (e as? AddonFailure)?.userMessage ?: e?.message ?: "Failed", ms)
            }
            return v
        }

        if (m.declaresAny(Resources.CATALOG)) {
            val cat = m.usableCatalogs.firstOrNull { !it.needsInput }
            if (cat == null) out += TestStep("catalog", TestStep.Status.Skipped, "No catalog that works without input", 0)
            else {
                val r = timed("catalog", { client.catalog(base, cat.type, cat.id, force = true) }) { if (it.metas.isEmpty()) "Answered, but listed no titles" else null }
                r?.metas?.firstOrNull()?.let { sampleType = it.type.ifBlank { cat.type }; sampleId = it.id }
            }
        }
        val type = sampleType; val id = sampleId
        for (res in listOf(Resources.META, Resources.STREAM, Resources.SUBTITLES)) {
            if (!m.declaresAny(res)) continue
            val label = if (res == Resources.STREAM) "stream" else res
            if (type == null || id == null) { out += TestStep(label, TestStep.Status.Skipped, "Needs a title from a catalog first", 0); continue }
            if (!m.declares(res, type, id)) { out += TestStep(label, TestStep.Status.Skipped, "Not offered for this sample title", 0); continue }
            when (res) {
                Resources.META -> timed(label, { client.meta(base, type, id, force = true) }) { null }
                Resources.STREAM -> timed(label, { client.streams(base, type, id, force = true) }) { null }
                else -> timed(label, { client.subtitles(base, type, id, force = true) }) { null }
            }
        }
        return out
    }
}
