package com.liuying.app.data.account

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccountApiTest {
    private lateinit var server: MockWebServer
    private lateinit var session: MemorySessionStore
    private lateinit var api: AccountApi

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        session = MemorySessionStore()
        api = AccountApi(OkHttpClient(), session, server.url("/").toString().trimEnd('/'))
    }

    @After fun tearDown() = server.shutdown()

    @Test fun loginStoresCookieAndSendsOriginAndJson() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Set-Cookie", "liuying_sid=abc123; Path=/; HttpOnly").setBody("""{"user":{"username":"mei"}}"""))
        val r = api.logIn("mei", "password1")
        assertEquals(ApiResult.Ok("mei"), r)
        assertEquals("abc123", session.sid); assertEquals("mei", session.username)
        val req = server.takeRequest()
        assertEquals("/api/auth/login", req.path)
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals(server.url("/").toString().trimEnd('/'), req.getHeader("Origin"))
        assertTrue(req.body.readUtf8().contains("\"username\":\"mei\""))
    }

    @Test fun errorsMapToFriendlyMessages() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_credentials","message":"x"}"""))
        val r = api.logIn("a", "b") as ApiResult.Err
        assertEquals("invalid_credentials", r.code); assertEquals("Wrong username or password.", r.message)
        assertNull(session.sid)
    }

    @Test fun syncRequestsCarryCookieAndParseState() = runBlocking {
        session.sid = "sid9"; session.username = "mei"
        server.enqueue(MockResponse().setBody("""{"sources":[{"url":"https://a/manifest.json","enabled":false}],"library":[{"id":"tt1","type":"movie","name":"X","poster":null,"addedAt":"2026-01-01T00:00:00.000Z"}],
            "progress":[{"mediaId":"tt1","episodeId":null,"positionMs":5,"durationMs":10,"watched":false,"dismissed":false,"updatedAt":99}],"settings":{},"serverTime":1}"""))
        val st = (api.getState() as ApiResult.Ok).value
        assertEquals("sid9", server.takeRequest().getHeader("Cookie")!!.substringAfter("liuying_sid="))
        assertEquals(false, st.sources[0].enabled); assertEquals("tt1", st.library[0].id)
        assertNull(st.progress[0].episodeId); assertEquals(99L, st.progress[0].updatedAt)
    }

    @Test fun unauthenticatedAndOffline() = runBlocking {
        session.sid = "old"
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthenticated"}"""))
        assertTrue((api.getState() as ApiResult.Err).unauthenticated)
        server.shutdown()
        assertTrue((api.getState() as ApiResult.Err).offline)
    }

    @Test fun logoutAlwaysClearsSession() = runBlocking {
        session.sid = "s"; session.username = "u"
        server.enqueue(MockResponse().setResponseCode(204))
        api.logOut()
        assertNull(session.sid); assertNull(session.username)
    }
}
