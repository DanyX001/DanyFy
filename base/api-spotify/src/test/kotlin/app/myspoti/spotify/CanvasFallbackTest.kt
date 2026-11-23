/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.spotify

import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import app.myspoti.spotify.gql.ClientTokenStore
import app.myspoti.spotify.gql.Gql
import app.myspoti.spotify.gql.GqlException
import app.myspoti.spotify.gql.HttpException
import app.myspoti.spotify.gql.SpotifyHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CanvasFallbackTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterEach fun tearDown() {
        scope.cancel()
    }

    private class TokenHost : BridgeHost {
        var tokensIssued = 0
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""").find(script) ?: return
            tokensIssued++
            val value = """{"accessToken":"web-$tokensIssued","expiresAtMs":${System.currentTimeMillis() + 3_600_000},"clientId":"cid","appVersion":"1.0","isAnonymous":false}"""
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":$value}""")
        }
    }

    private class MemoryStore : ClientTokenStore {
        private val saved = HashMap<String, String>()
        override fun load(deviceId: String) = saved[deviceId]
        override fun save(deviceId: String, text: String) { saved[deviceId] = text }
    }

    private fun extras(
        gqlImpl: Gql,
        tokenHost: TokenHost = TokenHost(),
        protoResponder: (trackRequestUri: String?, callIndex: Int) -> Pair<Int, ByteArray>,
    ): SpotifyTrackExtras {
        val bridge = Bridge(tokenHost, scope)
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            if (req.url.host == "clienttoken.spotify.com") {
                val json = """{"granted_token":{"token":"ct-1","expires_after_seconds":1209600}}"""
                return@Interceptor Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            calls++
            val reqBytes = req.body?.let { b -> Buffer().apply { b.writeTo(this) }.readByteArray() } ?: ByteArray(0)
            val entityUri = runCatching { parseProto(reqBytes).messages(1).firstOrNull()?.string(1) }.getOrNull()
            val (code, body) = protoResponder(entityUri, calls)
            Response.Builder()
                .request(req).protocol(Protocol.HTTP_1_1).code(code).message("HTTP $code")
                .body(body.toResponseBody("application/x-protobuf".toMediaType()))
                .build()
        }).build()
        val http = SpotifyHttp(client, bridge, tokenStore = MemoryStore())
        return SpotifyTrackExtras(gqlImpl, bridge, http)
    }

    @Test fun `uses GQL canvas when present without calling canvaz-cache`() = runBlocking {
        var protoCalls = 0
        val gql = object : Gql {
            override suspend fun query(name: String, variables: JsonObject): JsonElement =
                Bridge.json.parseToJsonElement("""{"data":{"trackUnion":{"canvas":{"uri":"spotify:canvas:c1","url":"https://canvaz.scdn.co/upload/c1.mp4","type":"VIDEO_LOOPING"}}}}""")
        }
        val ex = extras(gql) { _, _ -> protoCalls++; 200 to ByteArray(0) }
        val c = ex.canvas("spotify:track:t1")!!
        assertEquals("spotify:canvas:c1", c.uri)
        assertEquals("https://canvaz.scdn.co/upload/c1.mp4", c.videoUrl)
        assertEquals("VIDEO_LOOPING", c.type)
        assertEquals(0, protoCalls)
    }

    @Test fun `falls back to canvaz-cache proto when GQL returns null`() = runBlocking {
        val gql = object : Gql {
            override suspend fun query(name: String, variables: JsonObject): JsonElement =
                Bridge.json.parseToJsonElement("""{"data":{"trackUnion":{"canvas":null}}}""")
        }
        val payload = proto {
            message(1) {
                string(1, "c2")
                string(2, "https://canvaz.scdn.co/upload/c2.mp4")
                varint(4, 2) // VIDEO_LOOPING
                string(5, "spotify:track:t2")
                string(11, "spotify:canvas:c2")
            }
            varint(2, 3600)
        }
        val ex = extras(gql) { uri, _ ->
            assertEquals("spotify:track:t2", uri)
            200 to payload
        }
        val c = ex.canvas("spotify:track:t2")!!
        assertEquals("spotify:canvas:c2", c.uri)
        assertEquals("https://canvaz.scdn.co/upload/c2.mp4", c.videoUrl)
        assertEquals("VIDEO_LOOPING", c.type)
    }

    @Test fun `falls back to canvaz-cache image or gif when GQL throws and retries once on 401`() = runBlocking {
        val host = TokenHost()
        val gql = object : Gql {
            override suspend fun query(name: String, variables: JsonObject): JsonElement =
                throw GqlException("canvas", "persisted query failed")
        }
        val payload = proto {
            message(1) {
                string(1, "img1")
                string(2, "https://canvaz.scdn.co/upload/img1")
                varint(4, 0) // IMAGE
                string(5, "spotify:track:t3")
            }
        }
        val ex = extras(gql, host) { _, call ->
            if (call == 1) 401 to ByteArray(0) else 200 to payload
        }
        val c = ex.canvas("spotify:track:t3")!!
        assertEquals("spotify:canvas:img1", c.uri)
        assertEquals("https://canvaz.scdn.co/upload/img1", c.videoUrl)
        assertEquals("IMAGE", c.type)
        assertTrue(host.tokensIssued >= 2)
    }

    @Test fun `propagates error when both GQL and canvaz-cache fail`() = runBlocking {
        val gql = object : Gql {
            override suspend fun query(name: String, variables: JsonObject): JsonElement =
                throw HttpException(502, "bad gateway")
        }
        val ex = extras(gql) { _, _ -> 503 to "unavailable".toByteArray() }
        assertThrows<HttpException> {
            runBlocking { ex.canvas("spotify:track:t4") }
        }
    }

    @Test fun `returns null when both GQL and canvaz-cache report no canvas`() = runBlocking {
        val gql = object : Gql {
            override suspend fun query(name: String, variables: JsonObject): JsonElement =
                Bridge.json.parseToJsonElement("""{"data":{"trackUnion":{"canvas":null}}}""")
        }
        val emptyTtlOnly = proto { varint(2, 3600) }
        val ex = extras(gql) { _, _ -> 200 to emptyTtlOnly }
        assertNull(ex.canvas("spotify:track:t5"))
    }
}
