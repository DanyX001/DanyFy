package app.myspoti.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.io.File

/** The `canvas` hook (`canvas.provide` action): manifest wiring, result checks, and sandbox execution. */
class CanvasHookTest {
    private val track = TrackInfo("t1", "spotify:track:t1", "Blinding Lights", "The Weeknd", listOf("The Weeknd"), "After Hours", 200_000)

    private class QuietHost : Host {
        override fun log(extensionId: String, message: String) {}
        override fun storage(extensionId: String): Storage = MemoryStorage()
        override fun setting(extensionId: String, key: String): String? = null
    }

    @Test fun catalogAndCategoryWiring() {
        assertEquals(Hook.CANVAS, Hook.of("canvas"))
        assertEquals(Action.CANVAS_PROVIDE, Hook.CANVAS.action)
        assertEquals(Area.OTHER, Action.CANVAS_PROVIDE.area)
        assertEquals(Level.NORMAL, Action.CANVAS_PROVIDE.level)
        assertEquals(Mode.ADDITIVE, Action.CANVAS_PROVIDE.mode)
        assertTrue(Category.LOOK.fits(setOf(Action.CANVAS_PROVIDE)))
        assertEquals(HookBudgets.CANVAS, HookBudgets.of(Hook.CANVAS))
    }

    @Test fun canvasResultParseValidatesUrlAndType() {
        val parsed = CanvasResult.parse("""{"url":"https://mvod.itunes.apple.com/clip.mp4","type":"video_looping"}""")
        assertEquals(CanvasResult("https://mvod.itunes.apple.com/clip.mp4", "VIDEO_LOOPING"), parsed)
        val img = CanvasResult.parse("""{"url":"https://cdn.example.com/art.webp","type":"IMAGE","headers":{"Referer":"https://music.apple.com"}}""")
        assertEquals("IMAGE", img?.type)
        assertEquals(mapOf("Referer" to "https://music.apple.com"), img?.headers)
        assertNull(CanvasResult.parse("""{}"""))
        assertNull(CanvasResult.parse("""{"url":"   "}"""))
        try {
            CanvasResult.parse("""{"url":"http://127.0.0.1/clip.mp4"}""")
            fail("expected private URL rejection")
        } catch (_: ExtensionException) {}
        try {
            CanvasResult.parse("""{"url":"javascript:alert(1)"}""")
            fail("expected non-http scheme rejection")
        } catch (_: ExtensionException) {}
    }

    @Test fun runtimeProvidesCanvasWhenActionGranted() {
        val m = Manifest(
            id = "io.danyfy.applecanvas",
            name = "Apple Music Canvas",
            version = "1.0.0",
            author = "test",
            actions = listOf(ActionRequest("canvas.provide", "Provides looping album Canvas visuals")),
        )
        val js = """
            myspoti.provide('canvas', function (track) {
                return { url: 'https://mvod.itunes.apple.com/' + track.id + '.mp4', type: 'VIDEO_LOOPING' };
            });
        """.trimIndent()
        ExtensionRuntime(m, js, QuietHost(), grantedKeys = setOf("canvas.provide")).use { rt ->
            rt.start()
            assertTrue(rt.provides(Hook.CANVAS))
            val out = rt.call(Hook.CANVAS, track.toJson(), HookBudgets.CANVAS)!!
            val res = CanvasResult.parse(out)!!
            assertEquals("https://mvod.itunes.apple.com/t1.mp4", res.url)
            assertEquals("VIDEO_LOOPING", res.type)
        }
        // Without canvas.provide granted, registering the hook is rejected and does not provide canvas.
        ExtensionRuntime(m, js, QuietHost(), grantedKeys = emptySet()).use { rt ->
            rt.start()
            assertTrue(!rt.provides(Hook.CANVAS))
        }
    }

    @Test fun appleMusicCanvasPackageResolvesAndCachesHlsVariants() {
        val zipFile = File("/root/apple-canvas-extension/DanyFy-AppleCanvas-v1.0.1.zip")
        Assume.assumeTrue("Expected packaged zip at ${zipFile.absolutePath}", zipFile.exists())

        val pkg = ExtensionPackage.read(zipFile.readBytes())
        assertEquals(emptyList<String>(), pkg.manifest.problems())
        assertEquals("io.danyfy.applecanvas", pkg.manifest.id)
        assertEquals("1.0.1", pkg.manifest.version)
        assertEquals("look", pkg.manifest.category)
        assertTrue(Category.LOOK.fits(pkg.manifest.actionSet()))
        assertNotNull(pkg.icon)

        val settings = mutableMapOf<String, String>(
            "enabled" to "true",
            "quality" to "\"balanced\"",
        )
        val storage = MemoryStorage()
        val notes = mutableListOf<String>()
        var itunesCalls = 0
        var htmlCalls = 0

        val tallMaster = "https://mvod.itunes.apple.com/itunes-assets/tall/master.m3u8"
        val squareMaster = "https://mvod.itunes.apple.com/itunes-assets/square/master.m3u8"

        val mockHost = object : Host {
            override fun log(extensionId: String, message: String) {}
            override fun notify(extensionId: String, text: String) {
                notes += text
            }
            override fun storage(extensionId: String): Storage = storage
            override fun setting(extensionId: String, key: String): String? = settings[key]
            override fun http(extensionId: String, request: HttpRequest): HttpResponse {
                val u = request.url
                return when {
                    u.startsWith("https://itunes.apple.com/search") -> {
                        itunesCalls++
                        HttpResponse(
                            200,
                            mapOf("content-type" to "application/json"),
                            """{"resultCount":1,"results":[{"collectionName":"After Hours","artistName":"The Weeknd","collectionViewUrl":"https://music.apple.com/us/album/after-hours/1499385848?uo=4"}]}"""
                        )
                    }
                    u.startsWith("https://music.apple.com/us/album/after-hours/1499385848") -> {
                        htmlCalls++
                        HttpResponse(
                            200,
                            mapOf("content-type" to "text/html"),
                            """<html><script>{"videoArtwork":{"dictionary":{"motionDetailSquare":{"video":"$squareMaster"}}},"tallVideoArtwork":{"dictionary":{"motionDetailTall":{"video":"$tallMaster"}}}}</script></html>"""
                        )
                    }
                    u == tallMaster -> HttpResponse(
                        200,
                        mapOf("content-type" to "application/vnd.apple.mpegurl"),
                        """
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=900000,CODECS="hvc1.2",RESOLUTION=486x648
                        hevc.m3u8
                        #EXT-X-STREAM-INF:BANDWIDTH=1400000,CODECS="avc1.64001f",RESOLUTION=486x648
                        v486.m3u8
                        #EXT-X-STREAM-INF:BANDWIDTH=2700000,CODECS="avc1.64001f",RESOLUTION=664x886
                        v664.m3u8
                        #EXT-X-STREAM-INF:BANDWIDTH=9000000,CODECS="avc1.640028",RESOLUTION=1080x1440
                        v1080.m3u8
                        """.trimIndent()
                    )
                    u == squareMaster -> HttpResponse(
                        200,
                        mapOf("content-type" to "application/vnd.apple.mpegurl"),
                        """
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=1200000,CODECS="avc1.64001f",RESOLUTION=486x486
                        sq486.m3u8
                        #EXT-X-STREAM-INF:BANDWIDTH=2500000,CODECS="avc1.64001f",RESOLUTION=768x768
                        sq768.m3u8
                        """.trimIndent()
                    )
                    u.endsWith("/v664.m3u8") -> HttpResponse(
                        200,
                        mapOf("content-type" to "application/vnd.apple.mpegurl"),
                        "#EXTM3U\n#EXT-X-MAP:URI=\"clip_664x886.mp4\",BYTERANGE=\"800@0\"\n"
                    )
                    u.endsWith("/sq486.m3u8") -> HttpResponse(
                        200,
                        mapOf("content-type" to "application/vnd.apple.mpegurl"),
                        "#EXTM3U\n#EXT-X-MAP:URI=\"clip_486x486.mp4\",BYTERANGE=\"800@0\"\n"
                    )
                    else -> HttpResponse(404, emptyMap(), "not found")
                }
            }
        }

        val granted = pkg.manifest.actionSet().map { it.key }.toSet()
        ExtensionRuntime(pkg.manifest, pkg.script, mockHost, grantedKeys = granted).use { rt ->
            rt.start()
            assertTrue(rt.provides(Hook.CANVAS))

            // 1. Default format is square + balanced -> selects 768x768 H.264 HLS variant.
            val r1 = CanvasResult.parse(rt.call(Hook.CANVAS, track.toJson(), HookBudgets.CANVAS)!!)!!
            assertEquals("https://mvod.itunes.apple.com/itunes-assets/square/sq768.m3u8", r1.url)
            assertEquals("VIDEO_LOOPING", r1.type)
            assertEquals(1, itunesCalls)
            assertEquals(1, htmlCalls)

            // 2. Second track on the same album hits cache with zero network requests.
            val track2 = TrackInfo("t2", "spotify:track:t2", "Save Your Tears", "The Weeknd", listOf("The Weeknd"), "After Hours", 215_000)
            val r2 = CanvasResult.parse(rt.call(Hook.CANVAS, track2.toJson(), HookBudgets.CANVAS)!!)!!
            assertEquals(r1.url, r2.url)
            assertEquals(1, itunesCalls)
            assertEquals(1, htmlCalls)

            // 3. Switch to Tall + Balanced and Square + Saver -> resolves cached masters cleanly without re-querying HTML.
            settings["format"] = "\"tall\""
            settings["quality"] = "\"balanced\""
            val rTall = CanvasResult.parse(rt.call(Hook.CANVAS, track.toJson(), HookBudgets.CANVAS)!!)!!
            assertEquals("https://mvod.itunes.apple.com/itunes-assets/tall/v664.m3u8", rTall.url)
            assertEquals(1, itunesCalls)
            assertEquals(1, htmlCalls)

            settings["format"] = "\"square\""
            settings["quality"] = "\"saver\""
            val r3 = CanvasResult.parse(rt.call(Hook.CANVAS, track.toJson(), HookBudgets.CANVAS)!!)!!
            assertEquals("https://mvod.itunes.apple.com/itunes-assets/square/sq486.m3u8", r3.url)
            assertEquals(1, itunesCalls)
            assertEquals(1, htmlCalls)

            // 4. Clear cache button clears album cache and notifies user; next lookup re-fetches.
            rt.emit("setting", """{"key":"clear_cache","pressed":true}""")
            assertTrue(notes.any { it.contains("cache cleared", ignoreCase = true) })
            settings["format"] = "\"tall\""
            settings["quality"] = "\"balanced\""
            val r4 = CanvasResult.parse(rt.call(Hook.CANVAS, track.toJson(), HookBudgets.CANVAS)!!)!!
            assertEquals("https://mvod.itunes.apple.com/itunes-assets/tall/v664.m3u8", r4.url)
            assertEquals(2, itunesCalls)
            assertEquals(2, htmlCalls)

            // 5. Disabling extension returns null immediately.
            settings["enabled"] = "false"
            assertNull(rt.call(Hook.CANVAS, track.toJson(), HookBudgets.CANVAS))
            assertEquals(2, itunesCalls)
        }
    }

    @Test fun appleMusicCanvasV104UsesAmpApiHandlesLargeHtmlAndCapsBalancedBitrate() {
        val zipFile = File("/root/apple-canvas-extension/DanyFy-AppleCanvas-v1.0.4.zip")
        Assume.assumeTrue("Expected packaged zip at ${zipFile.absolutePath}", zipFile.exists())

        val pkg = ExtensionPackage.read(zipFile.readBytes())
        assertEquals(emptyList<String>(), pkg.manifest.problems())
        assertEquals("io.danyfy.applecanvas", pkg.manifest.id)
        assertEquals("1.0.4", pkg.manifest.version)
        assertEquals(5, pkg.manifest.versionCode)
        assertTrue(pkg.manifest.hosts().contains("amp-api.music.apple.com"))

        val jwtV1 = "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJBTVBXZWJQbGF5IiwiZXhwIjo0MTAyNDQ0ODAwfQ.sig_v1_0123456789abcdef0123456789"
        val jwtV2 = "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJBTVBXZWJQbGF5IiwiZXhwIjo0MTAyNDQ0ODAxfQ.sig_v2_0123456789abcdef0123456789"
        var currentJwt = jwtV1
        var bundleCalls = 0
        var ampSearchCalls = 0
        var otherVersionsCalls = 0
        var force401Once = false
        var failAmpApi = false
        var itunesCalls = 0

        val storage = MemoryStorage()
        val settings = mutableMapOf("enabled" to "true", "quality" to "\"balanced\"")

        val squareMaster = "https://mvod.itunes.apple.com/itunes-assets/square/master.m3u8"
        val tallMaster = "https://mvod.itunes.apple.com/itunes-assets/tall/master.m3u8"
        val cyrMaster = "https://mvod.itunes.apple.com/itunes-assets/cyr/master.m3u8"
        val szaMaster = "https://mvod.itunes.apple.com/itunes-assets/sza/master.m3u8"
        val fbMaster = "https://mvod.itunes.apple.com/itunes-assets/fb/master.m3u8"

        // Exceed prelude.js MAX_INPUT / 4 (2,097,152 chars) so any full-string regex throws RangeError.
        val oversizedPad = "x".repeat(2_600_000)

        val mockHost = object : Host {
            override fun log(extensionId: String, message: String) {}
            override fun storage(extensionId: String): Storage = storage
            override fun setting(extensionId: String, key: String): String? = settings[key]
            override fun http(extensionId: String, request: HttpRequest): HttpResponse {
                val u = request.url
                return when {
                    u == "https://music.apple.com/us/search" -> HttpResponse(
                        200,
                        mapOf("content-type" to "text/html"),
                        """<html><!--$oversizedPad--><script type="module" src="/assets/index~mock123.js"></script></html>"""
                    )
                    u == "https://music.apple.com/assets/index~mock123.js" -> {
                        bundleCalls++
                        // Also exceed 2MB in bundle and put a false-positive "eyJ" prefix earlier in the bundle.
                        HttpResponse(200, mapOf("content-type" to "application/javascript"), "/*$oversizedPad*/const noise='eyJnotjwt';const tk='$currentJwt';")
                    }
                    u.startsWith("https://amp-api.music.apple.com/") -> {
                        if (failAmpApi) return HttpResponse(503, emptyMap(), "unavailable")
                        if (force401Once) {
                            force401Once = false
                            return HttpResponse(401, emptyMap(), "unauthorized")
                        }
                        when {
                            u.contains("/search?") && u.contains("albums") && (u.contains("UTOPIA") || u.contains("utopia")) -> {
                                ampSearchCalls++
                                HttpResponse(
                                    200,
                                    mapOf("content-type" to "application/json"),
                                    """{"results":{"albums":{"data":[{"id":"1708274558","attributes":{"name":"UTOPIA","artistName":"Travis Scott"}}]},"songs":{"data":[]}}}"""
                                )
                            }
                            u.startsWith("https://amp-api.music.apple.com/v1/catalog/us/albums/1708274558") -> {
                                otherVersionsCalls++
                                HttpResponse(
                                    200,
                                    mapOf("content-type" to "application/json"),
                                    """{"data":[{"id":"1708274558","attributes":{"name":"UTOPIA"},"views":{"other-versions":{"data":[{"id":"1699712635","attributes":{"name":"UTOPIA","editorialVideo":{"motionDetailSquare":{"video":"$squareMaster"},"motionDetailTall":{"video":"$tallMaster"}}}}]}}}]}"""
                                )
                            }
                            u.contains("/search?") && u.contains("%D0%A3%D0%BB%D0%B8%D1%86%D0%B0") -> {
                                ampSearchCalls++
                                HttpResponse(
                                    200,
                                    mapOf("content-type" to "application/json"),
                                    """{"results":{"albums":{"data":[{"id":"15000001","attributes":{"name":"Улица 36","artistName":"Скриптонит","editorialVideo":{"motionDetailSquare":{"video":"$cyrMaster"}}}}]}}}"""
                                )
                            }
                            u.contains("/search?") && u.contains("SOS") -> {
                                ampSearchCalls++
                                val hasAnimated = !u.contains("LANA")
                                val ev = if (hasAnimated) ""","editorialVideo":{"motionDetailSquare":{"video":"$szaMaster"}}""" else ""
                                val albName = if (hasAnimated) "SOS" else "SOS Deluxe: LANA"
                                HttpResponse(
                                    200,
                                    mapOf("content-type" to "application/json"),
                                    """{"results":{"albums":{"data":[{"id":"1657869377","attributes":{"name":"$albName","artistName":"SZA"$ev}}]}}}"""
                                )
                            }
                            u.contains("/search?") && u.contains("Dawn") -> {
                                ampSearchCalls++
                                assertEquals("Bearer $jwtV2", request.headers["Authorization"])
                                HttpResponse(
                                    200,
                                    mapOf("content-type" to "application/json"),
                                    """{"results":{"albums":{"data":[{"id":"1618519111","attributes":{"name":"Dawn FM","artistName":"The Weeknd","editorialVideo":{"motionDetailSquare":{"video":"$squareMaster"}}}}]}}}"""
                                )
                            }
                            else -> HttpResponse(200, mapOf("content-type" to "application/json"), """{"results":{"albums":{"data":[]},"songs":{"data":[]}}}""")
                        }
                    }
                    u.startsWith("https://itunes.apple.com/search") -> {
                        itunesCalls++
                        HttpResponse(
                            200,
                            mapOf("content-type" to "application/json"),
                            """{"resultCount":1,"results":[{"collectionName":"FallbackLP","artistName":"Fallback Artist","collectionViewUrl":"https://music.apple.com/us/album/fallbacklp/9990001"}]}"""
                        )
                    }
                    u.startsWith("https://music.apple.com/us/album/fallbacklp/9990001") -> HttpResponse(
                        200,
                        mapOf("content-type" to "text/html"),
                        """<html><script>{"videoArtwork":{"dictionary":{"motionDetailSquare":{"video":"$fbMaster"}}}}</script></html>"""
                    )
                    u == squareMaster -> HttpResponse(
                        200,
                        mapOf("content-type" to "application/vnd.apple.mpegurl"),
                        """
                        #EXTM3U
                        #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=790000,BANDWIDTH=800000,CODECS="avc1.64001f",RESOLUTION=486x486
                        sq486_saver.m3u8
                        #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=1130000,BANDWIDTH=1190000,CODECS="avc1.64001f",RESOLUTION=486x486
                        sq486_bal.m3u8
                        #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=2200000,BANDWIDTH=2250000,CODECS="avc1.64001f",RESOLUTION=768x768
                        sq768.m3u8
                        #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=2850000,BANDWIDTH=2900000,CODECS="avc1.64001f",RESOLUTION=960x960
                        sq960.m3u8
                        #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=10100000,BANDWIDTH=10300000,CODECS="avc1.640020",RESOLUTION=1080x1080
                        sq1080.m3u8
                        """.trimIndent()
                    )
                    u == cyrMaster || u == szaMaster || u == fbMaster -> {
                        val prefix = when (u) {
                            cyrMaster -> "cyr"
                            szaMaster -> "sza"
                            else -> "fb"
                        }
                        HttpResponse(
                            200,
                            mapOf("content-type" to "application/vnd.apple.mpegurl"),
                            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2500000,CODECS=\"avc1.64001f\",RESOLUTION=768x768\n${prefix}768.m3u8\n"
                        )
                    }
                    u == tallMaster -> HttpResponse(
                        200,
                        mapOf("content-type" to "application/vnd.apple.mpegurl"),
                        """
                        #EXTM3U
                        #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=1120000,BANDWIDTH=1180000,CODECS="avc1.64001f",RESOLUTION=486x648
                        tall486.m3u8
                        #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=2150000,BANDWIDTH=2200000,CODECS="avc1.64001f",RESOLUTION=664x886
                        tall664.m3u8
                        #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=9900000,BANDWIDTH=10100000,CODECS="avc1.640020",RESOLUTION=1080x1440
                        tall1080.m3u8
                        """.trimIndent()
                    )
                    else -> HttpResponse(404, emptyMap(), "not found: $u")
                }
            }
        }

        val granted = pkg.manifest.actionSet().map { it.key }.toSet()
        ExtensionRuntime(pkg.manifest, pkg.script, mockHost, grantedKeys = granted).use { rt ->
            rt.start()
            assertTrue(rt.provides(Hook.CANVAS))

            // 1. Clean edition search hit without editorialVideo falls through to views=other-versions and finds animated Explicit edition (Balanced picks <=1.6 Mbps 486x486, not 2.8M/10M).
            val utopia1 = TrackInfo("u1", "spotify:track:u1", "FE!N", "Travis Scott", listOf("Travis Scott"), "UTOPIA", 191_000)
            val rUtopia = CanvasResult.parse(rt.call(Hook.CANVAS, utopia1.toJson(), HookBudgets.CANVAS)!!)!!
            assertEquals("https://mvod.itunes.apple.com/itunes-assets/square/sq486_bal.m3u8", rUtopia.url)
            assertEquals(1, bundleCalls)
            assertEquals(1, otherVersionsCalls)

            // Switching preset to High picks 960x960 @ 2.85M (<=3.2M), Saver picks 486x486 @ 790k, Tall picks portrait 486x648.
            settings["quality"] = "\"high\""
            val rUtopiaHigh = CanvasResult.parse(rt.call(Hook.CANVAS, utopia1.toJson(), HookBudgets.CANVAS)!!)!!
            assertEquals("https://mvod.itunes.apple.com/itunes-assets/square/sq960.m3u8", rUtopiaHigh.url)
            settings["quality"] = "\"saver\""
            val rUtopiaSaver = CanvasResult.parse(rt.call(Hook.CANVAS, utopia1.toJson(), HookBudgets.CANVAS)!!)!!
            assertEquals("https://mvod.itunes.apple.com/itunes-assets/square/sq486_saver.m3u8", rUtopiaSaver.url)
            settings["format"] = "\"tall\""
            settings["quality"] = "\"balanced\""
            val rUtopiaTall = CanvasResult.parse(rt.call(Hook.CANVAS, utopia1.toJson(), HookBudgets.CANVAS)!!)!!
            assertEquals("https://mvod.itunes.apple.com/itunes-assets/tall/tall486.m3u8", rUtopiaTall.url)
            settings["format"] = "\"square\""
            settings["quality"] = "\"balanced\""

            // 2. Non-Latin Cyrillic track preserves Unicode tokens and resolves cleanly.
            val cyr = TrackInfo("c1", "spotify:track:c1", "Притон", "Скриптонит", listOf("Скриптонит"), "Улица 36", 210_000)
            val rCyr = CanvasResult.parse(rt.call(Hook.CANVAS, cyr.toJson(), HookBudgets.CANVAS)!!)!!
            assertEquals("https://mvod.itunes.apple.com/itunes-assets/cyr/cyr768.m3u8", rCyr.url)
            assertEquals(1, bundleCalls)

            // 3. Deluxe subtitle ("SOS Deluxe: LANA") strips down to animated base album ("SOS").
            val sza = TrackInfo("s1", "spotify:track:s1", "BMF", "SZA", listOf("SZA"), "SOS Deluxe: LANA", 180_000)
            val rSza = CanvasResult.parse(rt.call(Hook.CANVAS, sza.toJson(), HookBudgets.CANVAS)!!)!!
            assertEquals("https://mvod.itunes.apple.com/itunes-assets/sza/sza768.m3u8", rSza.url)

            // 4. 401 on expired JWT forces single token refresh and retries amp-api cleanly.
            currentJwt = jwtV2
            force401Once = true
            val dawn = TrackInfo("d1", "spotify:track:d1", "Gasoline", "The Weeknd", listOf("The Weeknd"), "Dawn FM (Alternate World)", 212_000)
            val rDawn = CanvasResult.parse(rt.call(Hook.CANVAS, dawn.toJson(), HookBudgets.CANVAS)!!)!!
            assertEquals("https://mvod.itunes.apple.com/itunes-assets/square/sq486_bal.m3u8", rDawn.url)
            assertEquals(2, bundleCalls)

            // 5. Complete amp-api outage falls back to iTunes search + parallel HTML scraping.
            failAmpApi = true
            val fb = TrackInfo("f1", "spotify:track:f1", "Intro", "Fallback Artist", listOf("Fallback Artist"), "FallbackLP", 150_000)
            val rFb = CanvasResult.parse(rt.call(Hook.CANVAS, fb.toJson(), HookBudgets.CANVAS)!!)!!
            assertEquals("https://mvod.itunes.apple.com/itunes-assets/fb/fb768.m3u8", rFb.url)
            assertEquals(1, itunesCalls)
        }
    }
}
