package com.music.spotui.di

import app.myspoti.api.Device
import app.myspoti.engine.Cluster

/** Pure Connect device snapshot produced by merging WebView discovery and Engine dealer clusters. */
internal data class ConnectDeviceEntry(
    val id: String,
    val name: String,
    val type: String,
    val isSelf: Boolean,
    val isActive: Boolean,
)

/**
 * Pure state helpers for deciding when hardware/UI volume controls drive a remote Cast/Connect
 * device versus local phone media volume, and for merging Connect device discovery sources.
 */
internal object RemoteVolumeState {
    /**
     * Returns true when remote volume controls should be active instead of local phone `STREAM_MUSIC`.
     *
     * Explicit local/native playback or an active pull-back window clears remote volume right away
     * even before Spotify's cluster/WebView state finishes settling.
     */
    fun isRemoteActive(
        castActive: Boolean,
        localActive: Boolean,
        nativeHoldsPlayback: Boolean,
        returningToSelf: Boolean,
        activeDevicePresent: Boolean,
        chosenRemotePresent: Boolean,
        clusterKnown: Boolean,
        clusterHasRemote: Boolean,
        spotifyPlaysElsewhere: Boolean,
        selfActiveConfirmed: Boolean = false,
    ): Boolean {
        if (castActive) return true
        if (localActive || nativeHoldsPlayback || returningToSelf || selfActiveConfirmed) return false
        if (activeDevicePresent || chosenRemotePresent) return true
        if (clusterKnown) return clusterHasRemote
        return spotifyPlaysElsewhere
    }

    /**
     * Returns true when starting or shuffling a Spotify track/context must command the active
     * external Spotify Connect player rather than pulling playback back to this phone or diverting
     * into phone-side extension audio.
     */
    fun shouldKeepRemotePlayback(
        castActive: Boolean,
        hasConnectedRemote: Boolean,
    ): Boolean = !castActive && hasConnectedRemote

    /**
     * Merges Spotify WebView [apiDevices] with the native engine's pushed dealer [cluster], using
     * live cluster membership and [Cluster.activeDeviceId] whenever [cluster] is present so TVs
     * and speakers appear immediately without waiting on WebView polling.
     */
    fun mergeConnectDevices(
        apiDevices: List<Device>,
        cluster: Cluster?,
        selfId: String?,
    ): List<ConnectDeviceEntry> {
        val merged = LinkedHashMap<String, ConnectDeviceEntry>()
        val clusterActiveId = cluster?.activeDeviceId
        val hasCluster = cluster != null
        for (d in apiDevices) {
            if (d.id.isBlank()) continue
            val isSelf = d.local || (selfId != null && d.id == selfId)
            val isActive = if (hasCluster) d.id == clusterActiveId else d.active
            merged[d.id] = ConnectDeviceEntry(d.id, d.name, d.type, isSelf, isActive)
        }
        for (cd in cluster?.devices.orEmpty()) {
            if (cd.id.isBlank()) continue
            val existing = merged[cd.id]
            val isSelf = (selfId != null && cd.id == selfId) || (existing?.isSelf == true)
            val isActive = cd.id == clusterActiveId
            val name = cd.name.ifBlank { existing?.name.orEmpty() }.ifBlank { "Spotify Connect" }
            val type = cd.type.ifBlank { existing?.type.orEmpty() }.ifBlank { "COMPUTER" }
            merged[cd.id] = ConnectDeviceEntry(cd.id, name, type, isSelf, isActive)
        }
        return merged.values.sortedWith(
            compareByDescending<ConnectDeviceEntry> { it.isSelf }
                .thenByDescending { selfId != null && it.id == selfId }
                .thenBy { it.name.lowercase() },
        )
    }

    /** Convert a `0.0..1.0` volume ratio to a rounded `0..100` integer percentage. */
    fun toPercent(ratio: Double?): Int? =
        ratio?.takeIf { !it.isNaN() && it >= 0.0 }
            ?.let { kotlin.math.round(it * 100.0).toInt().coerceIn(0, 100) }

    /** Convert a `0.0f..1.0f` Cast volume ratio to a rounded `0..100` integer percentage. */
    fun toPercent(ratio: Float?): Int? =
        ratio?.takeIf { !it.isNaN() && it >= 0f }
            ?.let { kotlin.math.round(it * 100f).toInt().coerceIn(0, 100) }

    /**
     * Returns true when refreshing the Connect device list confirms any previously picked remote
     * device is no longer active (keeps [chosenRemote] across empty/transient responses).
     */
    fun shouldClearChosenRemoteOnDevices(
        list: List<ConnectDeviceEntry>,
        transferring: Boolean,
        nativeHoldsPlayback: Boolean,
    ): Boolean {
        if (nativeHoldsPlayback || list.any { it.isSelf && it.isActive }) return true
        val remoteActive = list.any { it.isActive && !it.isSelf }
        return !remoteActive && !transferring && list.isNotEmpty()
    }

    /**
     * Picks the best raw Connect volume reading (`0.0..1.0`, or `< 0.0` for fixed-volume hardware),
     * preserving negative fixed-volume flags while preferring live dealer [clusterVolume] over
     * potentially stale [apiVolume].
     */
    fun resolveRemoteMuteLevel(clusterVolume: Double?, apiVolume: Double?): Double? {
        val api = apiVolume?.takeIf { !it.isNaN() }
        if (api != null && api < 0.0) return api
        val cluster = clusterVolume?.takeIf { !it.isNaN() }
        if (cluster != null && cluster < 0.0) return cluster
        return cluster ?: api
    }
}

enum class VolumeSource { CLUSTER, API }

data class RemoteVolumeSample(
    val source: VolumeSource,
    val deviceId: String,
    val percent: Int,
    val atMs: Long,
)

/**
 * Reconciles remote volume readings coming from both the live dealer Cluster stream ([VolumeSource.CLUSTER])
 * and REST/WebView device polls ([VolumeSource.API]) without letting an unchanged cached reading
 * overwrite a newer change from the other source or a recent local phone adjustment.
 */
class RemoteVolumeTracker {
    var trackedDeviceId: String? = null
        private set
    private var lastClusterPercent: Int? = null
    private var lastClusterChangeMs: Long = 0L
    private var lastApiPercent: Int? = null
    private var lastApiChangeMs: Long = 0L
    private var lastLocalSetMs: Long = 0L

    fun onLocalSet(deviceId: String?, nowMs: Long) {
        if (!deviceId.isNullOrBlank()) trackedDeviceId = deviceId
        lastLocalSetMs = nowMs
    }

    fun reset() {
        trackedDeviceId = null
        lastClusterPercent = null
        lastClusterChangeMs = 0L
        lastApiPercent = null
        lastApiChangeMs = 0L
        lastLocalSetMs = 0L
    }

    fun accept(sample: RemoteVolumeSample, holdUntilMs: Long): Int? {
        val percent = sample.percent.coerceIn(0, 100)
        val deviceChanged = sample.deviceId != trackedDeviceId
        if (deviceChanged) {
            trackedDeviceId = sample.deviceId
            lastClusterPercent = null
            lastClusterChangeMs = 0L
            lastApiPercent = null
            lastApiChangeMs = 0L
        }

        if (!deviceChanged && sample.atMs <= holdUntilMs) {
            return null
        }

        val prevForSource = when (sample.source) {
            VolumeSource.CLUSTER -> lastClusterPercent
            VolumeSource.API -> lastApiPercent
        }
        val sourceChangeMs = when (sample.source) {
            VolumeSource.CLUSTER -> lastClusterChangeMs
            VolumeSource.API -> lastApiChangeMs
        }
        val otherChangeMs = when (sample.source) {
            VolumeSource.CLUSTER -> lastApiChangeMs
            VolumeSource.API -> lastClusterChangeMs
        }

        val changedFromSource = prevForSource == null || prevForSource != percent
        when (sample.source) {
            VolumeSource.CLUSTER -> {
                lastClusterPercent = percent
                if (changedFromSource) lastClusterChangeMs = sample.atMs
            }
            VolumeSource.API -> {
                lastApiPercent = percent
                if (changedFromSource) lastApiChangeMs = sample.atMs
            }
        }

        if (deviceChanged || changedFromSource) return percent
        if (otherChangeMs > sourceChangeMs) return null
        if (lastLocalSetMs > sourceChangeMs && otherChangeMs <= lastLocalSetMs) {
            when (sample.source) {
                VolumeSource.CLUSTER -> lastClusterChangeMs = sample.atMs
                VolumeSource.API -> lastApiChangeMs = sample.atMs
            }
            return percent
        }
        return null
    }
}
