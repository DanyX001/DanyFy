/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.ui.immersive.player

import android.app.Application
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.music.spotui.data.preferences.Look
import com.music.spotui.data.preferences.PlayerSourceLinePref
import com.music.spotui.di.Graph
import com.music.spotui.di.Spatial
import com.music.spotui.ui.components.ProvideWindowSize
import com.music.spotui.ui.look.LocalLook
import com.music.spotui.ui.look.ScrubSpeed
import com.music.spotui.ui.look.ThinSlider
import com.music.spotui.ui.look.scrubSpeedForVerticalDp
import com.music.spotui.ui.theme.SpotuiTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w891dp-h411dp-land-xxhdpi", application = Application::class)
class ImmersiveVolumeAndLandscapeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun initGraph() {
        Graph.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun swipeOnlySliderIgnoresTapAndTriggersGrabBounceOnHoldBeforeRelativeSwipe() {
        var volume by mutableFloatStateOf(0.35f)
        val grabs = mutableListOf<Boolean>()
        var calls = 0

        rule.setContent {
            Box(Modifier.width(300.dp)) {
                ThinSlider(
                    value = { volume },
                    onValueChange = {
                        volume = it
                        calls++
                    },
                    swipeOnly = true,
                    onGrabChanged = { grabs.add(it) },
                    modifier = Modifier.testTag("vol"),
                )
            }
        }

        val node = rule.onNodeWithTag("vol")
        // Bare click near 85% width must NOT jump volume or trigger a grab bounce.
        node.performTouchInput { click(Offset(width * 0.85f, centerY)) }
        rule.waitForIdle()
        assertEquals(0.35f, volume, 0.001f)
        assertEquals(0, calls)
        assertTrue(grabs.isEmpty())

        // Press and hold past hold-grab delay -> latches grab animation without altering value,
        // then swiping right applies relative delta instead of snapping to finger X.
        node.performTouchInput { down(Offset(width * 0.20f, centerY)) }
        rule.mainClock.advanceTimeBy(180)
        rule.waitForIdle()
        assertEquals(listOf(true), grabs)
        assertEquals(0.35f, volume, 0.001f)

        node.performTouchInput {
            moveBy(Offset(width * 0.30f, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf(true, false), grabs)
        assertTrue("Volume should increase moderately by relative drag, not snap to 0.50: $volume", volume > 0.50f && volume < 0.72f)
    }

    @Test
    fun swipeOnlySliderYieldsVerticalSwipe() {
        var volume by mutableFloatStateOf(0.42f)
        rule.setContent {
            Box(Modifier.width(300.dp)) {
                ThinSlider(
                    value = { volume },
                    onValueChange = { volume = it },
                    swipeOnly = true,
                    modifier = Modifier.testTag("vol"),
                )
            }
        }
        rule.onNodeWithTag("vol").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 140f)
        }
        rule.waitForIdle()
        assertEquals(0.42f, volume, 0.001f)
    }

    @Test
    fun playerVolumeDragTracksSnapshotAndThrottlesToStep() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val audio = ctx.getSystemService(AudioManager::class.java)!!
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vol = PlayerVolume(audio, scope)
            assertFalse(vol.dragging)

            vol.drag(0.50f)
            assertTrue(vol.dragging)
            assertEquals(0.50f, vol.dragFraction, 0.001f)
            assertEquals(kotlin.math.round(0.50f * max).toInt(), audio.getStreamVolume(AudioManager.STREAM_MUSIC))

            vol.drag(0.80f)
            assertEquals(0.80f, vol.dragFraction, 0.001f)
            assertEquals(kotlin.math.round(0.80f * max).toInt(), audio.getStreamVolume(AudioManager.STREAM_MUSIC))

            vol.release()
            assertFalse(vol.dragging)
            assertEquals(0.80f, vol.level.value, 0.001f)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun landscapePhoneShowsVolumeAndAppleMusicSplitLyricsWithArtworkTapBack() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SpotuiTheme {
                CompositionLocalProvider(
                    LocalLook provides Look.IMMERSIVE,
                    androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides rule.activity,
                ) {
                    ProvideWindowSize {
                        Box(Modifier.fillMaxSize().background(Color.Black)) {
                            ImmersivePlayer(
                                state = ImmersivePlayerState(
                                    title = "Magnolia",
                                    artist = "Playboi Carti",
                                    album = "Playboi Carti",
                                    explicit = true,
                                    playing = true,
                                    liked = false,
                                    positionMs = { 42_000L },
                                    durationMs = 181_000L,
                                ),
                                actions = ImmersivePlayerActions(),
                            )
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(600)

        // Controls panel shows Pause and Volume bar unclipped even on 411dp tall landscape phone.
        rule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        rule.onNodeWithContentDescription("Volume down").assertIsDisplayed()
        rule.onNodeWithTag("landscapeArtwork").assertIsDisplayed()
        val volNode = rule.onNodeWithContentDescription("Volume down").fetchSemanticsNode()
        val rootHeight = rule.onRoot().fetchSemanticsNode().boundsInRoot.height
        assertTrue("Volume row should fit inside 411dp landscape viewport: ${volNode.boundsInRoot.bottom} <= $rootHeight", volNode.boundsInRoot.bottom <= rootHeight)

        // Open Lyrics -> Apple Music landscape resting layout: left artwork stays, credits + ★/••• pinned top right,
        // NO Switch-off-lyrics header button, and playback controls stay hidden cleanly.
        rule.onNodeWithContentDescription("Lyrics").performClick()
        rule.mainClock.advanceTimeBy(600)

        rule.onNodeWithTag("landscapeArtwork").assertIsDisplayed()
        rule.onNodeWithText("Magnolia").assertIsDisplayed()
        rule.onNodeWithText("Playboi Carti").assertIsDisplayed()
        val splitArt = rule.onNodeWithTag("landscapeArtwork").fetchSemanticsNode().boundsInRoot
        val splitTitle = rule.onNodeWithText("Magnolia").fetchSemanticsNode().boundsInRoot
        assertTrue("Left artwork stays cleanly left of right-pane credits: ${splitArt.right} < ${splitTitle.left}", splitArt.right < splitTitle.left)
        assertTrue("Track title stays pinned near top of right column: ${splitTitle.top} < ${rootHeight * 0.35f}", splitTitle.top < rootHeight * 0.35f)
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Pause")).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Volume down")).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Close the lyrics")).fetchSemanticsNodes().isEmpty())

        // Tapping inside the lyrics panel does NOT reveal playback controls.
        rule.onNodeWithTag("landscapePanelContent").performClick()
        rule.mainClock.advanceTimeBy(400)
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag("landscapeSplitControls")).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Pause")).fetchSemanticsNodes().isEmpty())

        // Tapping left artwork (outside lyrics) shows Portrait-style controls ON THE LYRICS (RIGHT) SIDE for ~2.8s, then auto-hides.
        rule.onNodeWithTag("landscapeArtwork").performClick()
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("landscapeSplitControls").assertIsDisplayed()
        val controlsBounds = rule.onNodeWithTag("landscapeSplitControls").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Temporary controls must appear on the right/lyrics side (to the right of left artwork): ${controlsBounds.left} >= ${splitArt.right}",
            controlsBounds.left >= splitArt.right,
        )
        rule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        rule.onNodeWithContentDescription("Volume down").assertIsDisplayed()
        rule.onNodeWithContentDescription("Close the lyrics").assertIsDisplayed()

        // After ~3 seconds with no input, controls fade back out cleanly.
        rule.mainClock.advanceTimeBy(3100)
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Pause")).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Close the lyrics")).fetchSemanticsNodes().isEmpty())

        // Tapping non-lyrics empty space around left edge also reveals controls, and tapping the Lyrics toggle closes Lyrics cleanly.
        val rootBounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput { click(Offset(6f, rootBounds.height * 0.5f)) }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithContentDescription("Close the lyrics").assertIsDisplayed().performClick()
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        rule.onNodeWithContentDescription("Volume down").assertIsDisplayed()
    }

    @Test
    fun variableScrubSpeedAndAudioQualityBadgeMapCleanly() {
        assertEquals(ScrubSpeed.FULL, scrubSpeedForVerticalDp(10f))
        assertEquals(ScrubSpeed.HALF, scrubSpeedForVerticalDp(40f))
        assertEquals(ScrubSpeed.QUARTER, scrubSpeedForVerticalDp(85f))
        assertEquals(ScrubSpeed.FINE, scrubSpeedForVerticalDp(130f))

        assertEquals("Lossless", formatQualityBadge("Download · FLAC 44.1 kHz", 44_100, 16, spatialOn = false)?.text)
        assertEquals("Hi-Res Lossless", formatQualityBadge("Local · FLAC 96 kHz", 96_000, 24, spatialOn = false)?.text)
        assertEquals("Spatial Audio", formatQualityBadge("", 0, 16, spatialOn = true)?.text)
        assertEquals("AAC · 256 kbps", formatQualityBadge("Spotify · AAC · 256 kbit/s", 0, 16, spatialOn = false)?.text)
    }

    @Test
    fun qualityPillAppearsOnlyUnderScrubberAndNotUnderSongTitle() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val prevSpatialPref = com.music.spotui.data.preferences.EqualizerPref.spatial
        val prevSpatial = Spatial.current
        val prevSourcePref = PlayerSourceLinePref.on
        com.music.spotui.data.preferences.EqualizerPref.setSpatial(ctx, true)
        Spatial.current = Spatial.DEFAULT_WIDTH
        PlayerSourceLinePref.set(ctx, true)
        try {
            var sourceClicks = 0
            rule.setContent {
                SpotuiTheme {
                    CompositionLocalProvider(
                        LocalLook provides Look.IMMERSIVE,
                        androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides rule.activity,
                        LocalPlayerTint provides Color.White,
                    ) {
                        ProvideWindowSize {
                            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(Color.Black)) {
                                Box(Modifier.weight(1f)) {
                                    ImmersivePlayer(
                                        state = ImmersivePlayerState(
                                            title = "Magnolia",
                                            artist = "Playboi Carti",
                                            album = "Playboi Carti",
                                            playing = true,
                                            positionMs = { 30_000L },
                                            durationMs = 180_000L,
                                        ),
                                        actions = ImmersivePlayerActions(),
                                    )
                                }
                                Box(Modifier.width(360.dp).testTag("scrubberWrap")) {
                                    PlayerScrubber(
                                        state = ImmersivePlayerState(
                                            title = "Magnolia",
                                            artist = "Playboi Carti",
                                            positionMs = { 30_000L },
                                            durationMs = 180_000L,
                                        ),
                                        onSeek = {},
                                        onSource = { sourceClicks++ },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            rule.mainClock.advanceTimeBy(600)

            // One "Spatial Audio" pill in ImmersivePlayer + one in the standalone PlayerScrubber below (none under Title/Artist).
            val nodes = rule.onAllNodes(androidx.compose.ui.test.hasText("Spatial Audio")).fetchSemanticsNodes()
            assertEquals(2, nodes.size)
            val playerTitleBottom = rule.onNodeWithText("Magnolia").fetchSemanticsNode().boundsInRoot.bottom
            val scrubberBadge = nodes[0]
            assertTrue("Quality badge should sit below title/artist, not inside header credits: ${scrubberBadge.boundsInRoot.top} > $playerTitleBottom", scrubberBadge.boundsInRoot.top > playerTitleBottom)

            rule.onAllNodes(androidx.compose.ui.test.hasText("Spatial Audio"))[1].performClick()
            assertEquals(1, sourceClicks)

            // Turning off "Source and quality in the player" hides the pill cleanly.
            PlayerSourceLinePref.set(ctx, false)
            rule.mainClock.advanceTimeBy(200)
            assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText("Spatial Audio")).fetchSemanticsNodes().isEmpty())
        } finally {
            com.music.spotui.data.preferences.EqualizerPref.setSpatial(ctx, prevSpatialPref)
            Spatial.current = prevSpatial
            PlayerSourceLinePref.set(ctx, prevSourcePref)
        }
    }

    @Test
    fun dockArtTransformAlignsCoverWithMiniPlayerAndShareCardRendersCleanly() {
        val miniRect = androidx.compose.ui.geometry.Rect(30f, 1820f, 150f, 1940f) // 120x120 px at bottom-left
        val atRest = computeDockArtTransform(
            restLeftInSheet = 100f,
            restTopInSheet = 240f,
            restWidth = 800f,
            restHeight = 800f,
            miniArtInWindow = miniRect,
            sheetTranslationY = 0f,
            dockFraction = 0f,
        )
        assertEquals(1f, atRest.scale, 0.001f)
        assertEquals(0f, atRest.translationX, 0.001f)
        assertEquals(0f, atRest.translationY, 0.001f)

        val docked = computeDockArtTransform(
            restLeftInSheet = 100f,
            restTopInSheet = 240f,
            restWidth = 800f,
            restHeight = 800f,
            miniArtInWindow = miniRect,
            sheetTranslationY = 1800f,
            dockFraction = 1f,
        )
        // Mini size / rest size = 120 / 800 = 0.15; rest center = (500, 640); mini center in sheet = (90, 1880 - 1800 = 80)
        assertEquals(0.15f, docked.scale, 0.001f)
        assertEquals(-410f, docked.translationX, 0.001f)
        assertEquals(-560f, docked.translationY, 0.001f)

        // Missing mini-art falls back to identity transform.
        val fallback = computeDockArtTransform(100f, 240f, 800f, 800f, null, 1800f, 1f)
        assertEquals(1f, fallback.scale, 0.001f)
        assertEquals(0f, fallback.translationX, 0.001f)

        // ShareCard renders both album story card and lyric quote card cleanly at 1080x1920.
        val sampleBmp = android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888)
        val trackCard = com.music.spotui.ui.components.ShareCard.render("Magnolia", "Playboi Carti", sampleBmp, "DanyFy")
        assertEquals(1080, trackCard.width)
        assertEquals(1920, trackCard.height)
        val quoteCard = com.music.spotui.ui.components.ShareCard.renderLyricsCard(
            "Magnolia",
            "Playboi Carti",
            sampleBmp,
            listOf("In New York I Milly Rock", "Hide it in my sock", "Running from an opp"),
            "DanyFy",
        )
        assertEquals(1080, quoteCard.width)
        assertEquals(1920, quoteCard.height)
    }
}
