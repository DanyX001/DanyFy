package app.myspoti.host.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * Strips scheduled ad transitions out of Spotify `track-playback` state-machine responses
 * before they reach the WebView player.
 *
 * When a Free account plays through the WebView, Spotify schedules audio ads directly inside
 * `state_machine.tracks[]` ~1 song ahead. Blocking the ad CDN alone causes the WebView to
 * hang waiting for the refused AD track; rewriting `transitions.advance` and `transitions.skip_next`
 * past AD states onto the next real music state (walking only genuine `advance` / `skip_next` edges,
 * never display-only `show_next`) skips the ad break seamlessly before playback reaches it.
 */
internal object StateMachineSanitizer {
    private const val MAX_AD_HOPS = 16
    private val MACHINE_KEYS = listOf("state_machine", "previous_state_machine")
    private val EDGE_KEYS = listOf("advance", "skip_next")
    private val AD_HINT = Regex("\"AD\"|spotify:ad:|\"is_advertisement\"|\"ad_id\"")

    /**
     * Returns [body] with AD transitions bypassed onto subsequent non-AD tracks, or [body]
     * unchanged when no state-machine ad markers or bypassable ad transitions exist.
     */
    fun withoutAds(body: String): String {
        if ("\"state_machine\"" !in body && "\"previous_state_machine\"" !in body) return body
        if (!AD_HINT.containsMatchIn(body)) return body
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return body
        var changed = false
        for (key in MACHINE_KEYS) {
            val machine = root.optJSONObject(key) ?: continue
            if (sanitizeMachine(machine, updatedRef = if (key == "state_machine") root.optJSONObject("updated_state_ref") else null)) {
                changed = true
            }
        }
        return if (changed) root.toString() else body
    }

    private fun sanitizeMachine(machine: JSONObject, updatedRef: JSONObject? = null): Boolean {
        val tracks = machine.optJSONArray("tracks") ?: return false
        val states = machine.optJSONArray("states") ?: return false
        val adTracks = BooleanArray(tracks.length()) { i -> isAdTrack(tracks.optJSONObject(i)) }
        if (adTracks.none { it }) return false

        fun isAdState(stateIndex: Int): Boolean {
            val state = states.optJSONObject(stateIndex) ?: return false
            val trackIndex = if (state.isNull("track")) -1 else state.optInt("track", -1)
            return trackIndex in adTracks.indices && adTracks[trackIndex]
        }

        var changed = false
        if (updatedRef != null && !updatedRef.isNull("state_index")) {
            val cur = updatedRef.optInt("state_index", -1)
            if (cur in 0 until states.length() && isAdState(cur)) {
                findNextNonAdState(states, ::isAdState, cur, excludeIdx = cur)?.let { cleanCur ->
                    updatedRef.put("state_index", cleanCur)
                    changed = true
                }
            }
        }
        for (i in 0 until states.length()) {
            val state = states.optJSONObject(i) ?: continue
            val transitions = state.optJSONObject("transitions") ?: continue
            for (edgeKey in EDGE_KEYS) {
                val tr = transitions.optJSONObject(edgeKey) ?: continue
                val target = if (tr.isNull("state_index")) -1 else tr.optInt("state_index", -1)
                if (target !in 0 until states.length() || !isAdState(target)) continue
                val cleanTarget = findNextNonAdState(states, ::isAdState, target, excludeIdx = i) ?: continue
                tr.put("state_index", cleanTarget)
                if (edgeKey == "advance") tr.put("paused", false)
                changed = true
            }
        }
        return changed
    }

    private fun findNextNonAdState(
        states: JSONArray,
        isAdState: (Int) -> Boolean,
        startIdx: Int,
        excludeIdx: Int,
    ): Int? {
        val queue = ArrayDeque<Pair<Int, Int>>()
        val visited = HashSet<Int>()
        visited.add(startIdx)
        queue.add(startIdx to 0)
        while (queue.isNotEmpty()) {
            val (idx, hops) = queue.removeFirst()
            if (idx !in 0 until states.length()) continue
            if (!isAdState(idx) && idx != excludeIdx) return idx
            if (hops >= MAX_AD_HOPS) continue
            val transitions = states.optJSONObject(idx)?.optJSONObject("transitions") ?: continue
            for (edgeKey in EDGE_KEYS) {
                val edge = transitions.optJSONObject(edgeKey) ?: continue
                val nextIdx = if (edge.isNull("state_index")) -1 else edge.optInt("state_index", -1)
                if (nextIdx in 0 until states.length() && visited.add(nextIdx)) {
                    queue.add(nextIdx to (hops + 1))
                }
            }
        }
        return null
    }

    internal fun isAdTrack(track: JSONObject?): Boolean {
        if (track == null) return false
        if (!track.isNull("content_type") && track.optString("content_type").equals("AD", ignoreCase = true)) {
            return true
        }
        val rawUri = if (!track.isNull("uri")) track.optString("uri") else ""
        if (rawUri.startsWith("spotify:ad:")) return true
        val metadata = track.optJSONObject("metadata") ?: return false
        val metaUri = if (!metadata.isNull("uri")) metadata.optString("uri") else ""
        if (metaUri.startsWith("spotify:ad:")) return true
        val adv = metadata.opt("is_advertisement")
        if (adv == true || (adv as? String)?.equals("true", ignoreCase = true) == true) return true
        return !metadata.isNull("ad_id") && metadata.optString("ad_id").isNotBlank()
    }
}
