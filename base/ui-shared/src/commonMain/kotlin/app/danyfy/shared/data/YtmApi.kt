package app.danyfy.shared.data

import app.danyfy.shared.platform.PlatformRegistry
import app.danyfy.shared.platform.currentTimeSeconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class YtmApi(private val registry: PlatformRegistry = PlatformRegistry) {
    companion object {
        const val ORIGIN = "https://music.youtube.com"
        const val YOUTUBE_ORIGIN = "https://www.youtube.com"
        const val WEB_CLIENT_VERSION = "1.20260101.01.00"
        const val WEB_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private var cachedVisitorData: String? = null

    var cookies: String?
        get() = registry.storage.getString("ytm_cookies")
        set(value) = registry.storage.setString("ytm_cookies", value)

    val isSignedIn: Boolean
        get() = sapisidFromCookies(cookies) != null

    private fun baseContext(clientName: String = "WEB_REMIX", clientVersion: String = WEB_CLIENT_VERSION): JsonObject {
        val vis = cachedVisitorData ?: registry.storage.getString("ytm_visitor_data")
        return buildJsonObject {
            putJsonObject("client") {
                put("clientName", clientName)
                put("clientVersion", clientVersion)
                put("hl", "en")
                put("gl", "US")
                if (!vis.isNullOrBlank()) {
                    put("visitorData", vis)
                }
            }
        }
    }

    private fun webHeaders(): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to WEB_USER_AGENT,
            "Origin" to ORIGIN,
            "X-Origin" to ORIGIN,
            "Referer" to "$ORIGIN/",
            "X-YouTube-Client-Name" to "67",
            "X-YouTube-Client-Version" to WEB_CLIENT_VERSION,
            "Content-Type" to "application/json",
        )
        val vis = cachedVisitorData ?: registry.storage.getString("ytm_visitor_data")
        if (!vis.isNullOrBlank()) {
            headers["X-Goog-Visitor-Id"] = vis
        }
        val currentCookies = cookies
        if (!currentCookies.isNullOrBlank()) {
            headers["Cookie"] = currentCookies
            headers["X-Goog-AuthUser"] = "0"
            val sapisid = sapisidFromCookies(currentCookies)
            if (sapisid != null) {
                headers["Authorization"] = sapisidHashAuth(sapisid, currentTimeSeconds())
            }
        }
        return headers
    }

    private suspend fun postWeb(endpoint: String, body: JsonObject): JsonObject {
        val url = "$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false"
        val (code, responseBody) = registry.httpPostJson(url, webHeaders(), body.toString())
        if (code !in 200..299 || responseBody.isBlank()) {
            throw RuntimeException("YTM web request to $endpoint failed with code $code")
        }
        val root = YtmParser.json.parseToJsonElement(responseBody).jsonObject
        val visitorData = YtmParser.parseVisitorData(root)
        if (!visitorData.isNullOrBlank()) {
            cachedVisitorData = visitorData
            registry.storage.setString("ytm_visitor_data", visitorData)
        }
        return root
    }

    suspend fun home(): List<HomeFeedShelf> {
        val body = buildJsonObject {
            put("context", baseContext())
            put("browseId", "FEmusic_home")
        }
        val root = postWeb("browse", body)
        return YtmParser.parseHome(root)
    }

    suspend fun moodsAndGenres(): List<BrowseCategory> {
        val body = buildJsonObject {
            put("context", baseContext())
            put("browseId", "FEmusic_moods_and_genres")
        }
        val root = postWeb("browse", body)
        return YtmParser.parseMoodsAndGenres(root)
    }

    suspend fun search(query: String, params: String? = null): List<CollectionCard> {
        val body = buildJsonObject {
            put("context", baseContext())
            put("query", query)
            if (!params.isNullOrBlank()) {
                put("params", params)
            }
        }
        val root = postWeb("search", body)
        return YtmParser.parseSearch(root)
    }

    suspend fun browseCollection(browseId: String, params: String? = null): CollectionPage {
        val body = buildJsonObject {
            put("context", baseContext())
            put("browseId", browseId)
            if (!params.isNullOrBlank()) {
                put("params", params)
            }
        }
        val root = postWeb("browse", body)
        return YtmParser.parseCollection(browseId, root)
    }

    suspend fun nextRadio(videoId: String): List<TrackItem> {
        val body = buildJsonObject {
            put("context", baseContext())
            put("videoId", videoId)
            put("playlistId", "RDAMVM$videoId")
            put("isAudioOnly", true)
        }
        val root = postWeb("next", body)
        return YtmParser.parseWatchNext(root)
    }

    suspend fun resolveStreams(trackId: String): List<AudioStreamCandidate> {
        val vis = cachedVisitorData ?: registry.storage.getString("ytm_visitor_data")

        // Multi-client player request cascade:
        // 1. ANDROID_VR (Oculus Quest 3) - best unthrottled direct audio/mp4 AAC
        val vrUA = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
        val vrCandidates = callPlayer(
            trackId = trackId,
            clientName = "ANDROID_VR",
            clientVersion = "1.61.48",
            clientNumber = "28",
            userAgent = vrUA,
            visitorData = vis,
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            osName = "Android",
            osVersion = "12L",
        )
        if (vrCandidates.isNotEmpty()) return vrCandidates

        // 2. IOS client
        val iosUA = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X; en_US)"
        val iosCandidates = callPlayer(
            trackId = trackId,
            clientName = "IOS",
            clientVersion = "20.10.4",
            clientNumber = "5",
            userAgent = iosUA,
            visitorData = vis,
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            osName = "iOS",
            osVersion = "18.3.1.22D8075",
        )
        if (iosCandidates.isNotEmpty()) return iosCandidates

        // 3. IOS_MUSIC client
        val iosMusicUA = "com.google.ios.youtubemusic/8.10.1 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X; en_US)"
        val iosMusicCandidates = callPlayer(
            trackId = trackId,
            clientName = "IOS_MUSIC",
            clientVersion = "8.10.1",
            clientNumber = "26",
            userAgent = iosMusicUA,
            visitorData = vis,
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            osName = "iOS",
            osVersion = "18.3.1.22D8075",
        )
        if (iosMusicCandidates.isNotEmpty()) return iosMusicCandidates

        // 4. WEB_REMIX fallback
        val webCandidates = callPlayer(
            trackId = trackId,
            clientName = "WEB_REMIX",
            clientVersion = WEB_CLIENT_VERSION,
            clientNumber = "67",
            userAgent = WEB_USER_AGENT,
            visitorData = vis,
        )
        return webCandidates
    }

    private suspend fun callPlayer(
        trackId: String,
        clientName: String,
        clientVersion: String,
        clientNumber: String,
        userAgent: String,
        visitorData: String?,
        deviceMake: String? = null,
        deviceModel: String? = null,
        osName: String? = null,
        osVersion: String? = null,
    ): List<AudioStreamCandidate> {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", clientName)
                    put("clientVersion", clientVersion)
                    put("hl", "en")
                    put("gl", "US")
                    if (!visitorData.isNullOrBlank()) {
                        put("visitorData", visitorData)
                    }
                    if (deviceMake != null) put("deviceMake", deviceMake)
                    if (deviceModel != null) put("deviceModel", deviceModel)
                    if (osName != null) put("osName", osName)
                    if (osVersion != null) put("osVersion", osVersion)
                }
            }
            put("videoId", trackId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val headers = mutableMapOf(
            "User-Agent" to userAgent,
            "Origin" to YOUTUBE_ORIGIN,
            "X-YouTube-Client-Name" to clientNumber,
            "X-YouTube-Client-Version" to clientVersion,
            "Content-Type" to "application/json",
        )
        if (!visitorData.isNullOrBlank()) {
            headers["X-Goog-Visitor-Id"] = visitorData
        }
        val currentCookies = cookies
        if (!currentCookies.isNullOrBlank()) {
            headers["Cookie"] = currentCookies
            headers["X-Goog-AuthUser"] = "0"
            val sapisid = sapisidFromCookies(currentCookies)
            if (sapisid != null) {
                headers["Authorization"] = sapisidHashAuth(sapisid, currentTimeSeconds())
            }
        }

        return try {
            val url = "$YOUTUBE_ORIGIN/youtubei/v1/player?prettyPrint=false"
            val (status, res) = registry.httpPostJson(url, headers, body.toString())
            if (status in 200..299 && res.isNotBlank()) {
                val json = YtmParser.json.parseToJsonElement(res).jsonObject
                YtmParser.parsePlayerStreams(json, userAgent)
            } else {
                emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
