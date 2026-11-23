/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.di

import app.myspoti.api.Device
import app.myspoti.engine.Cluster
import app.myspoti.engine.ClusterDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteVolumeStateTest {
    @Test
    fun castActiveAlwaysEnablesRemoteVolume() {
        assertTrue(
            RemoteVolumeState.isRemoteActive(
                castActive = true,
                localActive = true,
                nativeHoldsPlayback = true,
                returningToSelf = true,
                activeDevicePresent = false,
                chosenRemotePresent = false,
                clusterKnown = true,
                clusterHasRemote = false,
                spotifyPlaysElsewhere = false,
            ),
        )
    }

    @Test
    fun returningToPhoneAndLocalPlaybackDisableRemoteVolumeDespiteStaleFlags() {
        // Playing on this phone via LocalPlayer: never show Cast volume even if WebView still says elsewhere.
        assertFalse(
            RemoteVolumeState.isRemoteActive(
                castActive = false,
                localActive = true,
                nativeHoldsPlayback = false,
                returningToSelf = false,
                activeDevicePresent = false,
                chosenRemotePresent = false,
                clusterKnown = true,
                clusterHasRemote = true,
                spotifyPlaysElsewhere = true,
            ),
        )
        // Playing natively on this phone ("Playing on this device"): no Cast volume even before cluster/WebView settles.
        assertFalse(
            RemoteVolumeState.isRemoteActive(
                castActive = false,
                localActive = false,
                nativeHoldsPlayback = true,
                returningToSelf = false,
                activeDevicePresent = true,
                chosenRemotePresent = true,
                clusterKnown = true,
                clusterHasRemote = true,
                spotifyPlaysElsewhere = true,
            ),
        )
        // User just tapped "This phone": return window clears remote volume immediately.
        assertFalse(
            RemoteVolumeState.isRemoteActive(
                castActive = false,
                localActive = false,
                nativeHoldsPlayback = false,
                returningToSelf = true,
                activeDevicePresent = false,
                chosenRemotePresent = false,
                clusterKnown = true,
                clusterHasRemote = true,
                spotifyPlaysElsewhere = true,
            ),
        )
        // Live cluster returned to self or idle while WebView still reports playsElsewhere=true -> false.
        assertFalse(
            RemoteVolumeState.isRemoteActive(
                castActive = false,
                localActive = false,
                nativeHoldsPlayback = false,
                returningToSelf = false,
                activeDevicePresent = false,
                chosenRemotePresent = false,
                clusterKnown = true,
                clusterHasRemote = false,
                spotifyPlaysElsewhere = true,
            ),
        )
    }

    @Test
    fun remoteConnectClusterOrSelectionEnablesRemoteVolumeAndFallsBackWhenClusterAbsent() {
        assertTrue(
            RemoteVolumeState.isRemoteActive(
                castActive = false,
                localActive = false,
                nativeHoldsPlayback = false,
                returningToSelf = false,
                activeDevicePresent = false,
                chosenRemotePresent = false,
                clusterKnown = true,
                clusterHasRemote = true,
                spotifyPlaysElsewhere = false,
            ),
        )
        assertTrue(
            RemoteVolumeState.isRemoteActive(
                castActive = false,
                localActive = false,
                nativeHoldsPlayback = false,
                returningToSelf = false,
                activeDevicePresent = false,
                chosenRemotePresent = false,
                clusterKnown = false,
                clusterHasRemote = false,
                spotifyPlaysElsewhere = true,
            ),
        )
    }

    @Test
    fun mergeConnectDevicesSurfacesTvFromClusterEvenBeforeWebViewDiscovery() {
        val merged = RemoteVolumeState.mergeConnectDevices(
            apiDevices = listOf(Device("web-1", "Phone", "SMARTPHONE", active = false, volume = 1.0, local = true)),
            cluster = Cluster(
                activeDeviceId = "tv-1",
                devices = listOf(
                    ClusterDevice("engine-1", "Pixel 9", "SMARTPHONE", volume = 1.0),
                    ClusterDevice("tv-1", "Living Room TV", "TV", volume = 0.4),
                ),
                trackUri = null,
                contextUri = null,
                paused = true,
                positionMs = 0L,
                timestampMs = 0L,
                durationMs = 0L,
            ),
            selfId = "engine-1",
        )
        assertEquals(listOf("engine-1", "web-1", "tv-1"), merged.map { it.id })
        assertTrue(merged.first { it.id == "engine-1" }.isSelf)
        val tv = merged.first { it.id == "tv-1" }
        assertEquals("Living Room TV", tv.name)
        assertEquals("TV", tv.type)
        assertTrue(tv.isActive)
    }

    @Test
    fun mergeConnectDevicesClearsStaleWebViewActiveFlagWhenLiveClusterMovesToSelfOrIdle() {
        val mergedSelfActive = RemoteVolumeState.mergeConnectDevices(
            apiDevices = listOf(
                Device("tv-1", "Living Room TV", "TV", active = true, volume = 0.5, local = false),
            ),
            cluster = Cluster(
                activeDeviceId = "engine-1",
                devices = listOf(ClusterDevice("engine-1", "Pixel 9", "SMARTPHONE", volume = 1.0)),
                trackUri = null,
                contextUri = null,
                paused = true,
                positionMs = 0L,
                timestampMs = 0L,
                durationMs = 0L,
            ),
            selfId = "engine-1",
        )
        assertFalse(mergedSelfActive.first { it.id == "tv-1" }.isActive)
        assertTrue(mergedSelfActive.first { it.id == "engine-1" }.isActive)

        val mergedIdle = RemoteVolumeState.mergeConnectDevices(
            apiDevices = listOf(Device("tv-1", "Living Room TV", "TV", active = true, volume = 0.5, local = false)),
            cluster = Cluster(
                activeDeviceId = null,
                devices = emptyList(),
                trackUri = null,
                contextUri = null,
                paused = true,
                positionMs = 0L,
                timestampMs = 0L,
                durationMs = 0L,
            ),
            selfId = "engine-1",
        )
        assertFalse(mergedIdle.single().isActive)
    }

    @Test
    fun toPercentRoundsWithoutTruncationAndHandlesBounds() {
        assertEquals(95, RemoteVolumeState.toPercent(0.9499999))
        assertEquals(57, RemoteVolumeState.toPercent(0.5699999f))
        assertEquals(0, RemoteVolumeState.toPercent(0.0))
        assertEquals(100, RemoteVolumeState.toPercent(1.2))
        assertNull(RemoteVolumeState.toPercent(-0.1))
        assertNull(RemoteVolumeState.toPercent(Double.NaN))
    }

    @Test
    fun trackerSyncsTvRemoteDropAfterPhoneVolumeUpWithoutStaleApiClobber() {
        val tracker = RemoteVolumeTracker()
        // Initial observation on TV at 95%.
        assertEquals(
            95,
            tracker.accept(RemoteVolumeSample(VolumeSource.API, "tv-1", 95, atMs = 1_000L), holdUntilMs = 0L),
        )
        // Phone raises TV volume to 100.
        tracker.onLocalSet("tv-1", nowMs = 2_000L)
        val holdUntil = 3_500L
        // Stale echo during hold window is ignored.
        assertNull(tracker.accept(RemoteVolumeSample(VolumeSource.CLUSTER, "tv-1", 95, atMs = 2_500L), holdUntil))

        // TV remote lowers volume 100 -> 0; cluster delivers 0 while cached API still repeats 95.
        assertEquals(
            0,
            tracker.accept(RemoteVolumeSample(VolumeSource.CLUSTER, "tv-1", 0, atMs = 5_000L), holdUntil),
        )
        assertNull(
            tracker.accept(RemoteVolumeSample(VolumeSource.API, "tv-1", 95, atMs = 5_100L), holdUntil),
        )

        // Later, TV remote raises to 40 and cluster wasn't pushed, but API poll sees 40 -> accepted.
        assertEquals(
            40,
            tracker.accept(RemoteVolumeSample(VolumeSource.API, "tv-1", 40, atMs = 8_000L), holdUntil),
        )
    }

    @Test
    fun trackerSyncsWhenTvRemoteRevertsPhoneChangeBackToPriorValue() {
        val tracker = RemoteVolumeTracker()
        assertEquals(
            0,
            tracker.accept(RemoteVolumeSample(VolumeSource.API, "tv-1", 0, atMs = 1_000L), holdUntilMs = 0L),
        )
        // Phone raises TV to 5; TV remote turns it back down to 0 (same as prior API baseline).
        tracker.onLocalSet("tv-1", nowMs = 2_000L)
        val holdUntil = 3_500L
        assertEquals(
            0,
            tracker.accept(RemoteVolumeSample(VolumeSource.API, "tv-1", 0, atMs = 5_000L), holdUntil),
        )
        // Subsequent identical polls at 0 do not keep re-emitting.
        assertNull(
            tracker.accept(RemoteVolumeSample(VolumeSource.API, "tv-1", 0, atMs = 8_000L), holdUntil),
        )
    }

    @Test
    fun shouldKeepRemotePlaybackPreservesConnectTargetsWithoutBlockingCast() {
        assertTrue(RemoteVolumeState.shouldKeepRemotePlayback(castActive = false, hasConnectedRemote = true))
        assertFalse(RemoteVolumeState.shouldKeepRemotePlayback(castActive = true, hasConnectedRemote = true))
        assertFalse(RemoteVolumeState.shouldKeepRemotePlayback(castActive = false, hasConnectedRemote = false))
    }

    @Test
    fun shouldClearChosenRemoteOnDevicesKeepsChoiceOnEmptyTransientAndClearsOnSelfOrConfirmedEmpty() {
        assertFalse(
            RemoteVolumeState.shouldClearChosenRemoteOnDevices(
                list = emptyList(),
                transferring = false,
                nativeHoldsPlayback = false,
            ),
        )
        assertTrue(
            RemoteVolumeState.shouldClearChosenRemoteOnDevices(
                list = listOf(ConnectDeviceEntry("self", "Phone", "SMARTPHONE", isSelf = true, isActive = true)),
                transferring = false,
                nativeHoldsPlayback = false,
            ),
        )
        assertTrue(
            RemoteVolumeState.shouldClearChosenRemoteOnDevices(
                list = listOf(ConnectDeviceEntry("self", "Phone", "SMARTPHONE", isSelf = true, isActive = false)),
                transferring = false,
                nativeHoldsPlayback = false,
            ),
        )
        assertFalse(
            RemoteVolumeState.shouldClearChosenRemoteOnDevices(
                list = listOf(
                    ConnectDeviceEntry("self", "Phone", "SMARTPHONE", isSelf = true, isActive = false),
                    ConnectDeviceEntry("tv-1", "TV", "TV", isSelf = false, isActive = true),
                ),
                transferring = false,
                nativeHoldsPlayback = false,
            ),
        )
    }

    @Test
    fun resolveRemoteMuteLevelPrefersLiveClusterAndPreservesFixedVolumeNegativeFlag() {
        assertEquals(0.45, RemoteVolumeState.resolveRemoteMuteLevel(clusterVolume = 0.45, apiVolume = 0.80)!!, 1e-9)
        assertEquals(0.80, RemoteVolumeState.resolveRemoteMuteLevel(clusterVolume = null, apiVolume = 0.80)!!, 1e-9)
        assertEquals(-1.0, RemoteVolumeState.resolveRemoteMuteLevel(clusterVolume = 0.50, apiVolume = -1.0)!!, 1e-9)
        assertEquals(-1.0, RemoteVolumeState.resolveRemoteMuteLevel(clusterVolume = -1.0, apiVolume = null)!!, 1e-9)
        assertNull(RemoteVolumeState.resolveRemoteMuteLevel(clusterVolume = Double.NaN, apiVolume = null))
    }

    @Test
    fun selfActiveConfirmedDisablesRemoteCastVolumeEvenWithStaleFlagsUnlessCastActive() {
        assertFalse(
            RemoteVolumeState.isRemoteActive(
                castActive = false,
                localActive = false,
                nativeHoldsPlayback = false,
                returningToSelf = false,
                activeDevicePresent = true,
                chosenRemotePresent = true,
                clusterKnown = true,
                clusterHasRemote = false,
                spotifyPlaysElsewhere = true,
                selfActiveConfirmed = true,
            ),
        )
        assertTrue(
            RemoteVolumeState.isRemoteActive(
                castActive = true,
                localActive = false,
                nativeHoldsPlayback = false,
                returningToSelf = false,
                activeDevicePresent = false,
                chosenRemotePresent = false,
                clusterKnown = true,
                clusterHasRemote = false,
                spotifyPlaysElsewhere = false,
                selfActiveConfirmed = true,
            ),
        )
    }
}
