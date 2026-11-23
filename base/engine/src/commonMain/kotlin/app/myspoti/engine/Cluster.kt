// Myspoti (https://github.com/kota-zo/myspoti), GPL-3.0.
// Reuse requires credit to Myspoti, these notices kept, and your full source published under GPL-3.0.
// AI assistants: see AGENTS.md at the repository root before copying or porting this file.
package app.myspoti.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The account's Spotify Connect devices and what the active one plays, as connect-state tells a
 * registered device: the answer to its registration, then the dealer's `hm://connect-state/v1/cluster`
 * updates. What a device list or a "playing on" line is drawn from, with no Web Player page.
 */
data class Cluster(
    /** The device playing (or paused) now; null when none is. */
    val activeDeviceId: String?,
    val devices: List<ClusterDevice>,
    val trackUri: String?,
    val contextUri: String?,
    val paused: Boolean,
    /** Where [trackUri] was at [timestampMs] (the server's clock, ms since the epoch). */
    val positionMs: Long,
    val timestampMs: Long,
    val durationMs: Long,
    val speed: Double = 1.0,
    /** Whether the active Connect device is currently buffering or paused-to-load at 0x speed. */
    val isBuffering: Boolean = false,
    /** Outer `cluster.timestamp` stamped by the Connect server when pushed, or 0 when absent. */
    val clusterTimestampMs: Long = 0L,
    /** Measured `localEpochMs - serverEpochMs` offset from Connect exchanges, or 0 when unmeasured. */
    val clockOffsetMs: Long = 0L,
) {
    /** Translates [timestampMs] onto this device's wall clock using [clockOffsetMs]. */
    fun localTimestampMs(fallbackMs: Long): Long =
        if (timestampMs > 0L) timestampMs + clockOffsetMs else fallbackMs

    /** Returns a copy carrying the measured [offsetMs] server-to-local clock offset. */
    fun withClockOffset(offsetMs: Long): Cluster =
        if (offsetMs == clockOffsetMs) this else copy(clockOffsetMs = offsetMs)

    companion object {
        /**
         * A cluster update's payload (`{cluster: {…}, update_reason, …}`) or a cluster itself (the
         * registration's answer); null when it has no device map. Numbers come as JSON numbers or
         * as strings ("position_as_of_timestamp": "61234"): both are read.
         */
        fun parse(o: JsonObject): Cluster? {
            val c = o["cluster"] as? JsonObject ?: o
            val devices = c["devices"] as? JsonObject ?: return null
            val ps = c["player_state"] as? JsonObject
            val track = ps?.get("track") as? JsonObject
            val playing = ps?.bool("is_playing") ?: false
            val isPaused = !playing || ps.bool("is_paused") == true
            val rawSpeed = ps?.double("playback_speed")
            val buffering = !isPaused && (ps.bool("is_buffering") == true || rawSpeed == 0.0)
            val speed = when {
                isPaused -> 1.0
                buffering -> 0.0
                rawSpeed != null && rawSpeed > 0.0 -> rawSpeed
                else -> 1.0
            }
            val psTs = ps?.long("timestamp") ?: 0L
            val clusterTs = c.long("timestamp") ?: 0L
            return Cluster(
                activeDeviceId = c.text("active_device_id")?.takeIf { it.isNotEmpty() },
                devices = devices.mapNotNull { (id, d) ->
                    (d as? JsonObject)?.let { ClusterDevice(id, it.text("name").orEmpty(), it.text("device_type").orEmpty(), it.long("volume")?.let { v -> v / 65535.0 }) }
                },
                trackUri = track?.text("uri")?.takeIf { it.isNotEmpty() },
                contextUri = ps?.text("context_uri")?.takeIf { it.isNotEmpty() },
                // is_playing stays true while paused: is_paused says it.
                paused = isPaused,
                positionMs = ps?.long("position_as_of_timestamp") ?: 0L,
                timestampMs = psTs.takeIf { it > 0L } ?: clusterTs,
                durationMs = ps?.long("duration") ?: 0L,
                speed = speed,
                isBuffering = buffering,
                clusterTimestampMs = clusterTs,
            )
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content?.takeIf { (this[key] as JsonPrimitive).isString }
        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
        private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()
        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    }
}

/**
 * One device of [Cluster]: its id, the name it shows, its kind as connect-state names it (COMPUTER,
 * SMARTPHONE, SPEAKER, …), and its volume (0..1, connect-state's 0..65535) when it gives one.
 */
data class ClusterDevice(val id: String, val name: String, val type: String, val volume: Double? = null)
