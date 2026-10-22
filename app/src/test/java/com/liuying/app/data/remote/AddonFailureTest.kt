package com.liuying.app.data.remote

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AddonFailureTest {
    private fun kind(e: IOException) = AddonFailure.fromIo(e).kind

    @Test fun resetsAndDropsReadAsUnreachable() {
        assertEquals(AddonErrorKind.NoConnection, kind(SocketException("Connection reset")))
        assertEquals(AddonErrorKind.NoConnection, kind(IOException("unexpected end of stream on http://x")))
        assertEquals(AddonErrorKind.NoConnection, kind(EOFException()))
        assertEquals(AddonErrorKind.NoConnection, kind(UnknownHostException("x")))
        assertEquals(AddonErrorKind.NoConnection, kind(SSLException("Connection reset", SocketException("reset"))))
    }

    @Test fun timeoutsAreTimeouts() {
        assertEquals(AddonErrorKind.Timeout, kind(SocketTimeoutException("timeout")))
        assertEquals(AddonErrorKind.Timeout, kind(IOException("Read timed out")))
    }

    @Test fun realTlsErrorsStayTls() {
        assertEquals(AddonErrorKind.Tls, kind(SSLHandshakeException("PKIX path building failed")))
    }

    @Test fun userMessagesSayCantReach() {
        for (k in listOf(AddonErrorKind.NoConnection, AddonErrorKind.Timeout)) {
            val m = AddonFailure(k, "x").userMessage
            assert(m.startsWith("Can't reach the source")) { m }
            assertFalse(m.contains("valid data"))
        }
    }
}

class QaSmallLogicTest {
    @Test fun playRouteArgIsConsumedOnce() {
        assertEquals("primary", com.liuying.app.ui.details.PlayRouteArg.effective("primary", false))
        org.junit.Assert.assertNull(com.liuying.app.ui.details.PlayRouteArg.effective("primary", true))
        org.junit.Assert.assertNull(com.liuying.app.ui.details.PlayRouteArg.effective(null, false))
    }

    @Test fun updaterProgressEmitsAtMostOncePerPercent() {
        val p = com.liuying.app.data.update.Updater.ProgressThrottle
        assertEquals(0, p.percent(0.004f)); assertEquals(1, p.percent(0.011f)); assertEquals(100, p.percent(1.5f))
    }
}
