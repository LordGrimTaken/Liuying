package com.liuying.app.data.party

import com.liuying.app.data.account.AccountApi
import com.liuying.app.data.account.ApiResult
import com.liuying.app.data.account.MemorySessionStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PartyApiTest {
    private lateinit var server: MockWebServer
    private lateinit var session: MemorySessionStore
    private lateinit var api: PartyApi

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        session = MemorySessionStore()
        api = PartyApi(AccountApi(OkHttpClient(), session, server.url("/").toString().trimEnd('/')))
    }

    @After fun tearDown() = server.shutdown()

    private fun stateJson(
        mediaId: String = "tt1",
        episodeId: String? = null,
        positionMs: Long = 1_500,
        playing: Boolean = true,
    ) = """{"mediaId":"$mediaId","episodeId":${if (episodeId == null) "null" else "\"$episodeId\""},""" +
        """"positionMs":$positionMs,"playing":$playing,"updatedAt":1700000000000,"hostName":"mei"}"""

    @Test fun createPostsTheTitleAndStateOverTheSession() = runBlocking {
        session.sid = "sid1"; session.username = "mei"
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"code":"ABCDEF","rev":1,"state":${stateJson(episodeId = "tt1:1:1", positionMs = 700)},"members":[{"user":1},{"user":2}]}"""
            )
        )
        val room = (api.create("tt1", "tt1:1:1", 700, true) as ApiResult.Ok).value
        assertEquals("ABCDEF", room.code)
        assertEquals(1L, room.rev)
        assertEquals(700L, room.state.positionMs)
        assertEquals("tt1:1:1", room.state.episodeId)
        assertEquals("mei", room.state.hostName)
        assertEquals(2, room.members)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/party", req.path)
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("sid1", req.getHeader("Cookie")!!.substringAfter("liuying_sid="))
        assertEquals(server.url("/").toString().trimEnd('/'), req.getHeader("Origin"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("tt1", body["mediaId"]!!.jsonPrimitive.content)
        assertEquals("tt1:1:1", body["episodeId"]!!.jsonPrimitive.content)
        assertEquals(700L, body["positionMs"]!!.jsonPrimitive.long)
        assertTrue(body["playing"]!!.jsonPrimitive.boolean)
        assertEquals("mei", body["name"]!!.jsonPrimitive.content)
    }

    @Test fun createSendsANullEpisodeAndOmitsAMissingName() = runBlocking {
        session.sid = "sid1"
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"code":"ABCDEF","rev":1,"state":${stateJson()},"members":1}"""))
        api.create("tt1", null, 0, false)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(body["episodeId"] is JsonNull)
        assertFalse(body.containsKey("name")) // signed in but no username cached: the server keeps the host name null
    }

    @Test fun getPollsTheRoomAndParsesTheState() = runBlocking {
        session.sid = "sid1"
        server.enqueue(MockResponse().setBody("""{"code":"ABCDEF","rev":9,"state":${stateJson(positionMs = 4_200, playing = false)},"members":3}"""))
        val room = (api.get("abcdef") as ApiResult.Ok).value
        assertEquals("ABCDEF", room.code)
        assertEquals(9L, room.rev)
        assertEquals(4_200L, room.state.positionMs)
        assertEquals(false, room.state.playing)
        assertEquals(3, room.members)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/party/ABCDEF", req.path)
        assertEquals("sid1", req.getHeader("Cookie")!!.substringAfter("liuying_sid="))
    }

    @Test fun updateSendsOnlyPositionAndPlayState() = runBlocking {
        session.sid = "sid1"
        server.enqueue(MockResponse().setBody("""{"rev":10,"state":${stateJson(positionMs = 9_000)}}"""))
        val room = (api.update("ABCDEF", 9_000, true) as ApiResult.Ok).value
        assertEquals("ABCDEF", room.code) // the response has no code: the requested one fills in
        assertEquals(10L, room.rev)
        assertEquals(9_000L, room.state.positionMs)
        assertEquals(0, room.members)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/party/ABCDEF", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(setOf("positionMs", "playing"), body.keys)
        assertEquals(9_000L, body["positionMs"]!!.jsonPrimitive.long)
        assertTrue(body["playing"]!!.jsonPrimitive.boolean)
    }

    @Test fun joinLeavesAndEndsHitTheirRoutes() = runBlocking {
        session.sid = "sid1"
        server.enqueue(MockResponse().setBody("""{"code":"ABCDEF","rev":4,"state":${stateJson()},"host":false}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))

        assertEquals("ABCDEF", (api.join("ABCDEF") as ApiResult.Ok).value.code)
        assertTrue(api.leave("ABCDEF") is ApiResult.Ok)
        assertTrue(api.end("ABCDEF") is ApiResult.Ok)

        val joinReq = server.takeRequest()
        assertEquals("POST", joinReq.method); assertEquals("/api/party/ABCDEF/join", joinReq.path)
        assertEquals(0, Json.parseToJsonElement(joinReq.body.readUtf8()).jsonObject.size)
        val leaveReq = server.takeRequest()
        assertEquals("POST", leaveReq.method); assertEquals("/api/party/ABCDEF/leave", leaveReq.path)
        val endReq = server.takeRequest()
        assertEquals("DELETE", endReq.method); assertEquals("/api/party/ABCDEF", endReq.path)
    }

    @Test fun serverErrorsKeepTheirCodes() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}"""))
        val notFound = api.get("ABCDEF") as ApiResult.Err
        assertEquals("not_found", notFound.code)

        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}"""))
        assertEquals("forbidden", (api.update("ABCDEF", 1, true) as ApiResult.Err).code)

        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthenticated"}"""))
        assertTrue((api.get("ABCDEF") as ApiResult.Err).unauthenticated)
    }

    @Test fun anInvalidCodeNeverReachesTheNetwork() = runBlocking {
        val r = api.join("ABC") as ApiResult.Err
        assertEquals("bad_request", r.code)
        assertFalse(r.offline) // a typo is not a connection problem
        assertEquals(0, server.requestCount)

        val link = api.get("https://www.liuyingapp.com/watch/?party=ABCDEF") as ApiResult.Err
        assertEquals("bad_request", link.code)
        assertEquals(0, server.requestCount)
    }

    @Test fun anUnexpectedBodyIsAnErrorNotACrash() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":"ABCDEF","rev":1}"""))
        assertEquals("bad_response", (api.get("ABCDEF") as ApiResult.Err).code)
        server.enqueue(MockResponse().setBody("not json"))
        assertEquals("bad_response", (api.get("ABCDEF") as ApiResult.Err).code)
    }
}
