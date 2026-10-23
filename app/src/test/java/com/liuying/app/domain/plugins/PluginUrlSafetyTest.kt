package com.liuying.app.domain.plugins

import com.liuying.app.domain.plugins.PluginUrlSafety.Verdict
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PluginUrlSafetyTest {
    private fun allowed(u: String) = PluginUrlSafety.check(u) is Verdict.Allowed
    private fun blocked(u: String) = PluginUrlSafety.check(u) is Verdict.Blocked

    @Test fun allowsPublicHttpAndHttps() {
        assertTrue(allowed("https://example.com/a?b=1"))
        assertTrue(allowed("http://cdn.example.org:8080/x"))
        assertTrue(allowed("https://93.184.216.34/"))
        assertTrue(allowed("https://[2606:2800:220:1:248:1893:25c8:1946]/"))
    }

    @Test fun blocksOtherSchemes() {
        listOf("file:///etc/passwd", "ftp://example.com/x", "javascript:alert(1)", "content://x/y", "data:text/plain,hi", "intent://x#Intent;end", "ws://example.com", "example.com/no-scheme", "")
            .forEach { assertTrue(it, blocked(it)) }
    }

    @Test fun blocksLoopbackPrivateAndLinkLocalLiterals() {
        listOf(
            "http://localhost/", "http://LOCALHOST:8080/", "http://foo.localhost/", "http://127.0.0.1/", "http://127.1.2.3/", "http://0.0.0.0/",
            "http://10.0.0.5/", "http://172.16.0.1/", "http://172.31.255.255/", "http://192.168.1.1/", "http://169.254.169.254/latest/meta-data",
            "http://100.64.0.1/", "http://198.18.0.1/", "http://224.0.0.1/", "http://255.255.255.255/", "http://240.0.0.1/",
            "http://[::1]/", "http://[::]/", "http://[fe80::1]/", "http://[fd00::1]/", "http://[fc12::1]/", "http://[::ffff:127.0.0.1]/", "http://[::ffff:192.168.0.1]/", "http://[2001:db8::1]/",
        ).forEach { assertTrue(it, blocked(it)) }
    }

    @Test fun allowsAddressesJustOutsidePrivateRanges() {
        listOf("http://172.15.0.1/", "http://172.32.0.1/", "http://100.63.255.255/", "http://100.128.0.1/", "http://11.0.0.1/", "http://192.169.0.1/").forEach { assertTrue(it, allowed(it)) }
    }

    @Test fun blocksLocalNamesAndAmbiguousNumericHosts() {
        listOf(
            "http://printer.local/", "http://router.lan/", "http://nas.home.arpa/", "http://service.internal/", "http://intranet/", "http://router/",
            "http://2130706433/", "http://0x7f.0.0.1/", "http://0177.0.0.1/", "http://127.1/", "http://localhost./",
        ).forEach { assertTrue(it, blocked(it)) }
    }

    @Test fun blocksCredentialsAndOverlongUrls() {
        assertTrue(blocked("https://user:pw@example.com/"))
        assertTrue(blocked("https://user@example.com/"))
        assertTrue(blocked("https://example.com/" + "a".repeat(5000)))
    }

    @Test fun addressRuleCoversResolvedAnswers() {
        assertTrue(PluginUrlSafety.isBlockedAddress(InetAddress.getByName("10.1.2.3")))
        assertTrue(PluginUrlSafety.isBlockedAddress(InetAddress.getByName("::1")))
        assertTrue(!PluginUrlSafety.isBlockedAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(!PluginUrlSafety.isBlockedAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test fun redirectsAreResolvedAndChecked() {
        val base = "https://a.example.com/dir/page".toHttpUrl()
        val ok = PluginUrlSafety.redirect(base, "/next") as Verdict.Allowed
        assertEquals("https://a.example.com/next", ok.url.toString())
        assertEquals("https://b.example.org/x", (PluginUrlSafety.redirect(base, "https://b.example.org/x") as Verdict.Allowed).url.toString())
        assertTrue(PluginUrlSafety.redirect(base, "http://127.0.0.1/admin") is Verdict.Blocked)
        assertTrue(PluginUrlSafety.redirect(base, "http://169.254.169.254/") is Verdict.Blocked)
        assertTrue(PluginUrlSafety.redirect(base, "file:///etc/hosts") is Verdict.Blocked)
        assertEquals(5, PluginUrlSafety.MAX_REDIRECTS)
    }
}
