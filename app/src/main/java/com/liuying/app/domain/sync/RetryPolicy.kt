package com.liuying.app.domain.sync

/**
 * Retry schedule for a failed sync round.
 *
 * A round used to fail silently: the status line said "Sync failed" and nothing was sent until the user
 * changed something again or restarted the app. This makes a transient failure retry itself, backing off
 * so an offline phone does not hammer the Worker.
 *
 * Pure on purpose — the whole schedule is unit tested without a coroutine or a clock.
 */
object RetryPolicy {
    /** First retry waits this long. */
    const val FIRST_DELAY_MS = 30_000L
    /** Never wait longer than this between attempts (15 minutes). */
    const val MAX_DELAY_MS = 15 * 60_000L
    /** A server Retry-After is honoured, but clamped into this window. */
    const val MIN_DELAY_MS = 5_000L
    /** Doubling stops after this many attempts; the delay stays at [MAX_DELAY_MS]. */
    const val MAX_DOUBLINGS = 5

    /**
     * Whether a failed request is worth retrying. 0 means the request never reached the server (offline),
     * 408/429 mean "later", 5xx mean the server broke. 4xx errors are the app's fault and will not heal.
     */
    fun isRetryable(http: Int): Boolean = http == 0 || http == 408 || http == 429 || http >= 500

    /**
     * Delay before the next attempt. [attempt] is 0 for the first retry after a failure. A server
     * Retry-After wins but is clamped, so a bad header cannot make the app spin or sleep for an hour.
     */
    fun nextDelay(attempt: Int, retryAfterSec: Int? = null): Long {
        if (retryAfterSec != null && retryAfterSec > 0) {
            return (retryAfterSec * 1000L).coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        }
        val doublings = attempt.coerceIn(0, MAX_DOUBLINGS)
        return (FIRST_DELAY_MS shl doublings).coerceAtMost(MAX_DELAY_MS)
    }
}
