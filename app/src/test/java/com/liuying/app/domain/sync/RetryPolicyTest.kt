package com.liuying.app.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    @Test
    fun `only transport, timeout, throttling and server faults are retried`() {
        assertTrue(RetryPolicy.isRetryable(0))     // offline: the request never left
        assertTrue(RetryPolicy.isRetryable(408))
        assertTrue(RetryPolicy.isRetryable(429))
        assertTrue(RetryPolicy.isRetryable(500))
        assertTrue(RetryPolicy.isRetryable(503))
        assertFalse(RetryPolicy.isRetryable(400))
        assertFalse(RetryPolicy.isRetryable(401))
        assertFalse(RetryPolicy.isRetryable(403))
        assertFalse(RetryPolicy.isRetryable(413))
    }

    @Test
    fun `delay doubles and then caps`() {
        assertEquals(30_000L, RetryPolicy.nextDelay(0))
        assertEquals(60_000L, RetryPolicy.nextDelay(1))
        assertEquals(120_000L, RetryPolicy.nextDelay(2))
        assertEquals(240_000L, RetryPolicy.nextDelay(3))
        assertEquals(480_000L, RetryPolicy.nextDelay(4))
        assertEquals(900_000L, RetryPolicy.nextDelay(5))      // 960 s capped to 15 min
        assertEquals(900_000L, RetryPolicy.nextDelay(9))      // stays capped
        assertEquals(900_000L, RetryPolicy.nextDelay(50))
    }

    @Test
    fun `a negative attempt behaves like the first retry`() {
        assertEquals(RetryPolicy.nextDelay(0), RetryPolicy.nextDelay(-3))
    }

    @Test
    fun `Retry-After is honoured inside the clamp`() {
        assertEquals(7_000L, RetryPolicy.nextDelay(4, retryAfterSec = 7))
        assertEquals(RetryPolicy.MIN_DELAY_MS, RetryPolicy.nextDelay(0, retryAfterSec = 1))
        assertEquals(RetryPolicy.MAX_DELAY_MS, RetryPolicy.nextDelay(0, retryAfterSec = 3_600))
        assertEquals(RetryPolicy.nextDelay(2), RetryPolicy.nextDelay(2, retryAfterSec = 0))
        assertEquals(RetryPolicy.nextDelay(2), RetryPolicy.nextDelay(2, retryAfterSec = -5))
    }
}
