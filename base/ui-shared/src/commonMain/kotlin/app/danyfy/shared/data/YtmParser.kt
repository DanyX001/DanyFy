package app.danyfy.shared.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object YtmParser {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun upgradeThumbnailUrl(url: String?): String? {
        if (url == null) return null
        return url.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
            .replace(Regex("=s\\d+"), "=s544")
    }

    fun parseVisitorData(root: JsonObject): String? {
        root["responseContext"]?.jsonObject?.get("visitorData")?.jsonPrimitive?.contentOrNull?.let {
            return it
        }
        return findStringRecursively(root, "visitorData")
    }

    fun parseHome(root: JsonObject): List<HomeFeedShelf> {
        val shelves = mutableListOf<HomeFeedShelf>()
        val contents = root["contents"]?.jsonObject
            ?.get("singleColumnBrowseResultsRenderer")?.jsonObject
            ?.get("tabs")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("tabRenderer")?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("sectionListRenderer")?.jsonObject
            ?.get("contents")?.jsonArray ?: JsonArray(emptyList())

        for (section in contents) {
            val carousel = section.jsonObject["musicCarouselShelfRenderer"]?.jsonObject ?: continue
            val header = carousel["header"]?.jsonObject
                ?.get("musicCarouselShelfBasicHeaderRenderer")?.jsonObject
            val title = runText(header?.get("title")) ?: continue
            val subtitle = runText(header?.get("strapline"))

            val items = mutableListOf<CollectionCard>()
            val itemElements = carousel["contents"]?.jsonArray ?: continue
            for (elem in itemElements) {
                val card = parseTwoRowOrResponsive(elem.jsonObject)
                if (card != null) items.add(card)
            }
            if (items.isNotEmpty()) {
                shelves.add(HomeFeedShelf(title = title, subtitle = subtitle, items = items))
            }
        }
        return shelves
    }

    fun parseMoodsAndGenres(root: JsonObject): List<BrowseCategory> {
        val list = mutableListOf<BrowseCategory>()
        val items = findArrayRecursively(root, "items") ?: findArrayRecursively(root, "contents")
        if (items != null) {
            for (elem in items) {
                val button = elem.jsonObject["musicNavigationButtonRenderer"]?.jsonObject ?: continue
                val text = runText(button["buttonText"]) ?: continue
                val endpoint = button["clickCommand"]?.jsonObject
                    ?.get("browseEndpoint")?.jsonObject
                val browseId = endpoint?.get("browseId")?.jsonPrimitive?.contentOrNull ?: ""
                val params = endpoint?.get("params")?.jsonPrimitive?.contentOrNull
                val colorInt = button["solid"]?.jsonObject?.get("leftStripeColor")?.jsonPrimitive?.longOrNull
                val hex = colorInt?.let { "#" + (it and 0xFFFFFFL).toString(16).padStart(6, '0') }
                list.add(BrowseCategory(id = browseId, title = text, colorHex = hex, params = params))
            }
        }
        return list
    }

    fun parseSearch(root: JsonObject): List<CollectionCard> {
        val results = mutableListOf<CollectionCard>()

        // 1. Top result card if present
        val cardShelf = findObjectRecursively(root, "musicCardShelfRenderer")
        if (cardShelf != null) {
            val title = runText(cardShelf["title"]) ?: ""
            val subtitle = runText(cardShelf["subtitle"]) ?: ""
            val browseId = cardShelf["onTap"]?.jsonObject
                ?.get("watchEndpoint")?.jsonObject?.get("videoId")?.jsonPrimitive?.contentOrNull
                ?: cardShelf["title"]?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("navigationEndpoint")?.jsonObject?.get("browseEndpoint")?.jsonObject?.get("browseId")?.jsonPrimitive?.contentOrNull
                ?: ""
            val thumb = findLargestThumbnail(cardShelf)
            if (title.isNotBlank()) {
                results.add(
                    CollectionCard(
                        id = browseId,
                        title = title,
                        subtitle = subtitle,
                        artworkUrl = upgradeThumbnailUrl(thumb),
                        kind = if (subtitle.contains("Artist", ignoreCase = true)) "artist" else "track"
                    )
                )
            }
        }

        // 2. All responsive and two-row items
        val allItems = findAllObjectsRecursively(root, listOf("musicResponsiveListItemRenderer", "musicTwoRowItemRenderer"))
        for (item in allItems) {
            val card = parseTwoRowOrResponsive(item)
            if (card != null && results.none { it.id == card.id }) {
                results.add(card)
            }
        }
        return results
    }

    fun parseCollection(browseId: String, root: JsonObject): CollectionPage {
        val header = findObjectRecursively(root, "musicResponsiveHeaderRenderer")
            ?: findObjectRecursively(root, "musicDetailHeaderRenderer")

        val title = runText(header?.get("title")) ?: "Collection"
        val subtitle = runText(header?.get("subtitle")) ?: ""
        val description = runText(header?.get("description")) ?: ""
        val artwork = upgradeThumbnailUrl(findLargestThumbnail(header ?: root))

        val isAlbum = browseId.startsWith("MPRE") || subtitle.contains("Album", ignoreCase = true)
        val isArtist = browseId.startsWith("UC") || subtitle.contains("Artist", ignoreCase = true)

        val tracks = mutableListOf<TrackItem>()
        val listItems = findAllObjectsRecursively(root, listOf("musicResponsiveListItemRenderer", "playlistPanelVideoRenderer"))
        for (item in listItems) {
            val track = parseTrackItem(item)
            if (track != null) tracks.add(track)
        }

        return CollectionPage(
            id = browseId,
            title = title,
            owner = subtitle,
            description = description,
            artworkUrl = artwork,
            tracks = tracks,
            isAlbum = isAlbum,
            isArtist = isArtist,
        )
    }

    fun parseWatchNext(root: JsonObject): List<TrackItem> {
        val tracks = mutableListOf<TrackItem>()
        val items = findAllObjectsRecursively(root, listOf("playlistPanelVideoRenderer"))
        for (elem in items) {
            val track = parseTrackItem(elem)
            if (track != null) tracks.add(track)
        }
        return tracks
    }

    fun parsePlayerStreams(root: JsonObject, userAgent: String): List<AudioStreamCandidate> {
        val candidates = mutableListOf<AudioStreamCandidate>()
        val streamingData = root["streamingData"]?.jsonObject ?: return emptyList()

        // Adaptive audio formats (audio/mp4)
        val adaptive = streamingData["adaptiveFormats"]?.jsonArray ?: JsonArray(emptyList())
        for (f in adaptive) {
            val obj = f.jsonObject
            val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: continue
            val mimeType = obj["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val itag = obj["itag"]?.jsonPrimitive?.intOrNull ?: 0
            val bitrate = obj["bitrate"]?.jsonPrimitive?.intOrNull ?: 0

            // Filter for audio/mp4 (AAC) - iOS AVPlayer cannot decode WebM/Opus
            if (mimeType.startsWith("audio/mp4") || mimeType.contains("codecs=\"mp4a")) {
                candidates.add(
                    AudioStreamCandidate(
                        url = url,
                        itag = itag,
                        mimeType = "audio/mp4",
                        bitrate = bitrate,
                        userAgent = userAgent,
                    )
                )
            }
        }
        candidates.sortByDescending { it.bitrate }

        // Fallback 1: HLS manifest URL if present
        streamingData["hlsManifestUrl"]?.jsonPrimitive?.contentOrNull?.let { hlsUrl ->
            candidates.add(
                AudioStreamCandidate(
                    url = hlsUrl,
                    itag = 999,
                    mimeType = "application/x-mpegURL",
                    bitrate = 128000,
                    userAgent = userAgent,
                )
            )
        }

        // Fallback 2: Muxed video/mp4 format 18
        val formats = streamingData["formats"]?.jsonArray ?: JsonArray(emptyList())
        for (f in formats) {
            val obj = f.jsonObject
            val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: continue
            val mimeType = obj["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val itag = obj["itag"]?.jsonPrimitive?.intOrNull ?: 0
            val bitrate = obj["bitrate"]?.jsonPrimitive?.intOrNull ?: 0
            if (mimeType.startsWith("video/mp4") && itag == 18) {
                candidates.add(
                    AudioStreamCandidate(
                        url = url,
                        itag = itag,
                        mimeType = "video/mp4",
                        bitrate = bitrate,
                        userAgent = userAgent,
                    )
                )
            }
        }

        return candidates
    }

    private fun parseTwoRowOrResponsive(obj: JsonObject): CollectionCard? {
        val renderer = obj["musicTwoRowItemRenderer"]?.jsonObject
            ?: obj["musicResponsiveListItemRenderer"]?.jsonObject
            ?: obj

        val title = runText(renderer["title"])
            ?: runText(renderer["flexColumns"]?.jsonArray?.firstOrNull()?.jsonObject?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject?.get("text"))
            ?: return null

        val subtitle = runText(renderer["subtitle"])
            ?: runText(renderer["flexColumns"]?.jsonArray?.getOrNull(1)?.jsonObject?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject?.get("text"))
            .orEmpty()

        val nav = renderer["navigationEndpoint"]?.jsonObject
            ?: renderer["overlay"]?.jsonObject?.get("musicItemThumbnailOverlayRenderer")?.jsonObject?.get("content")?.jsonObject?.get("musicPlayButtonRenderer")?.jsonObject?.get("playNavigationEndpoint")?.jsonObject
            ?: renderer["flexColumns"]?.jsonArray?.firstOrNull()?.jsonObject?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject?.get("text")?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject?.get("navigationEndpoint")?.jsonObject

        val browseId = nav?.get("browseEndpoint")?.jsonObject?.get("browseId")?.jsonPrimitive?.contentOrNull
            ?: nav?.get("watchEndpoint")?.jsonObject?.get("videoId")?.jsonPrimitive?.contentOrNull
            ?: nav?.get("watchEndpoint")?.jsonObject?.get("playlistId")?.jsonPrimitive?.contentOrNull
            ?: ""

        val pageType = nav?.get("browseEndpoint")?.jsonObject
            ?.get("browseEndpointContextSupportedConfigs")?.jsonObject
            ?.get("browseEndpointContextMusicConfig")?.jsonObject
            ?.get("pageType")?.jsonPrimitive?.contentOrNull

        val kind = when {
            pageType == "MUSIC_PAGE_TYPE_ALBUM" || browseId.startsWith("MPRE") -> "album"
            pageType == "MUSIC_PAGE_TYPE_ARTIST" || browseId.startsWith("UC") -> "artist"
            browseId.startsWith("RDAMVM") || browseId.startsWith("RDCLAK") -> "mix"
            nav?.get("watchEndpoint")?.jsonObject?.containsKey("videoId") == true -> "track"
            else -> "playlist"
        }

        val artworkUrl = upgradeThumbnailUrl(findLargestThumbnail(renderer))

        return CollectionCard(
            id = browseId,
            title = title,
            subtitle = subtitle,
            artworkUrl = artworkUrl,
            kind = kind,
        )
    }

    private fun parseTrackItem(obj: JsonObject): TrackItem? {
        val renderer = obj["musicResponsiveListItemRenderer"]?.jsonObject
            ?: obj["playlistPanelVideoRenderer"]?.jsonObject
            ?: obj

        val videoId = renderer["playlistItemData"]?.jsonObject?.get("videoId")?.jsonPrimitive?.contentOrNull
            ?: renderer["videoId"]?.jsonPrimitive?.contentOrNull
            ?: renderer["navigationEndpoint"]?.jsonObject?.get("watchEndpoint")?.jsonObject?.get("videoId")?.jsonPrimitive?.contentOrNull
            ?: renderer["overlay"]?.jsonObject?.get("musicItemThumbnailOverlayRenderer")?.jsonObject?.get("content")?.jsonObject?.get("musicPlayButtonRenderer")?.jsonObject?.get("playNavigationEndpoint")?.jsonObject?.get("watchEndpoint")?.jsonObject?.get("videoId")?.jsonPrimitive?.contentOrNull
            ?: ""

        val flexCols = renderer["flexColumns"]?.jsonArray
        val title = if (flexCols != null && flexCols.isNotEmpty()) {
            runText(flexCols[0].jsonObject["musicResponsiveListItemFlexColumnRenderer"]?.jsonObject?.get("text"))
        } else {
            runText(renderer["title"])
        } ?: return null

        val subtitle = if (flexCols != null && flexCols.size > 1) {
            runText(flexCols[1].jsonObject["musicResponsiveListItemFlexColumnRenderer"]?.jsonObject?.get("text"))
        } else {
            runText(renderer["longBylineText"]) ?: runText(renderer["shortBylineText"])
        }.orEmpty()

        val durationText = runText(
            renderer["fixedColumns"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("musicResponsiveListItemFixedColumnRenderer")?.jsonObject?.get("text")
        ) ?: runText(renderer["lengthText"]).orEmpty()

        val durationSeconds = parseDurationSeconds(durationText)
        val artwork = upgradeThumbnailUrl(findLargestThumbnail(renderer))

        val parts = subtitle.split("•").map { it.trim() }
        val artist = parts.firstOrNull() ?: subtitle
        val album = if (parts.size > 1) parts[1] else ""

        return TrackItem(
            id = videoId,
            title = title,
            artist = artist,
            album = album,
            durationSeconds = durationSeconds,
            durationText = durationText,
            artworkUrl = artwork,
        )
    }

    private fun parseDurationSeconds(text: String): Long {
        val parts = text.trim().split(':').mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0L
        }
    }

    private fun runText(elem: JsonElement?): String? {
        val obj = elem?.jsonObject ?: return null
        obj["runs"]?.jsonArray?.let { runs ->
            return runs.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }.takeIf { it.isNotBlank() }
        }
        obj["simpleText"]?.jsonPrimitive?.contentOrNull?.let { return it }
        return null
    }

    private fun findLargestThumbnail(obj: JsonObject): String? {
        var largestUrl: String? = null
        var largestDim = 0
        val thumbnails = findArrayRecursively(obj, "thumbnails")
        if (thumbnails != null) {
            for (t in thumbnails) {
                val url = t.jsonObject["url"]?.jsonPrimitive?.contentOrNull ?: continue
                val width = t.jsonObject["width"]?.jsonPrimitive?.intOrNull ?: 0
                if (width >= largestDim) {
                    largestDim = width
                    largestUrl = url
                }
            }
        }
        return largestUrl
    }

    private fun findStringRecursively(element: JsonElement, key: String): String? {
        if (element is JsonObject) {
            element[key]?.jsonPrimitive?.contentOrNull?.let { return it }
            for ((_, v) in element) {
                findStringRecursively(v, key)?.let { return it }
            }
        } else if (element is JsonArray) {
            for (item in element) {
                findStringRecursively(item, key)?.let { return it }
            }
        }
        return null
    }

    private fun findObjectRecursively(element: JsonElement, key: String): JsonObject? {
        if (element is JsonObject) {
            element[key]?.jsonObject?.let { return it }
            for ((_, v) in element) {
                findObjectRecursively(v, key)?.let { return it }
            }
        } else if (element is JsonArray) {
            for (item in element) {
                findObjectRecursively(item, key)?.let { return it }
            }
        }
        return null
    }

    private fun findArrayRecursively(element: JsonElement, key: String): JsonArray? {
        if (element is JsonObject) {
            element[key]?.jsonArray?.let { return it }
            for ((_, v) in element) {
                findArrayRecursively(v, key)?.let { return it }
            }
        } else if (element is JsonArray) {
            for (item in element) {
                findArrayRecursively(item, key)?.let { return it }
            }
        }
        return null
    }

    private fun findAllObjectsRecursively(element: JsonElement, keys: List<String>): List<JsonObject> {
        val list = mutableListOf<JsonObject>()
        fun walk(e: JsonElement) {
            if (e is JsonObject) {
                for (k in keys) {
                    e[k]?.jsonObject?.let { list.add(it) }
                }
                for ((_, v) in e) walk(v)
            } else if (e is JsonArray) {
                for (item in e) walk(item)
            }
        }
        walk(element)
        return list
    }
}
