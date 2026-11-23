package app.myspoti.host.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateMachineSanitizerTest {
    private fun edge(index: Int?): String =
        if (index == null) "null" else """{"state_index":$index,"paused":true,"active_alias":null}"""

    private fun state(
        trackIndex: Int,
        advance: Int?,
        skipNext: Int?,
        showNext: Int? = null,
    ): String =
        """{"state_id":"s$trackIndex","track":$trackIndex,"transitions":{"advance":${edge(advance)},"skip_next":${edge(skipNext)},"show_next":${edge(showNext)}}}"""

    private fun songTrack(name: String, extraMeta: String = ""): String =
        """{"content_type":"TRACK","metadata":{"uri":"spotify:track:$name"$extraMeta}}"""

    private fun adTrack(variant: Int): String = when (variant) {
        0 -> """{"content_type":"AD","metadata":{"uri":"spotify:ad:ad0"}}"""
        1 -> """{"content_type":"TRACK","metadata":{"uri":"spotify:ad:ad1"}}"""
        2 -> """{"content_type":"TRACK","metadata":{"uri":"spotify:track:x","is_advertisement":true}}"""
        3 -> """{"content_type":"TRACK","metadata":{"uri":"spotify:track:y","is_advertisement":"true"}}"""
        else -> """{"content_type":"TRACK","metadata":{"uri":"spotify:track:z","ad_id":"ad_999"}}"""
    }

    @Test
    fun singleAdOnAdvanceAndSkipNextBypassesDirectlyToNextSong() {
        val raw = """
            {
              "state_machine": {
                "state_machine_id": "m1",
                "tracks": [${songTrack("A")}, ${adTrack(0)}, ${songTrack("B")}],
                "states": [
                  ${state(0, advance = 1, skipNext = 1)},
                  ${state(1, advance = 2, skipNext = 2)},
                  ${state(2, advance = null, skipNext = null)}
                ]
              }
            }
        """.trimIndent()

        val cleaned = JSONObject(StateMachineSanitizer.withoutAds(raw))
        val tr0 = cleaned.getJSONObject("state_machine")
            .getJSONArray("states")
            .getJSONObject(0)
            .getJSONObject("transitions")
        assertEquals(2, tr0.getJSONObject("advance").getInt("state_index"))
        assertFalse(tr0.getJSONObject("advance").getBoolean("paused"))
        assertEquals(2, tr0.getJSONObject("skip_next").getInt("state_index"))
    }

    @Test
    fun chainedMultiAdBreakWalksPastEveryAdStateAcrossAllAdMarkers() {
        val raw = """
            {
              "state_machine": {
                "tracks": [${songTrack("A")}, ${adTrack(1)}, ${adTrack(2)}, ${adTrack(4)}, ${songTrack("B")}],
                "states": [
                  ${state(0, advance = 1, skipNext = null)},
                  ${state(1, advance = 2, skipNext = 2)},
                  ${state(2, advance = null, skipNext = 3)},
                  ${state(3, advance = 4, skipNext = null)},
                  ${state(4, advance = null, skipNext = null)}
                ]
              },
              "previous_state_machine": {
                "tracks": [${songTrack("P")}, ${adTrack(3)}, ${songTrack("Q")}],
                "states": [
                  ${state(0, advance = 1, skipNext = 1)},
                  ${state(1, advance = 2, skipNext = null)},
                  ${state(2, advance = null, skipNext = null)}
                ]
              }
            }
        """.trimIndent()

        val cleaned = JSONObject(StateMachineSanitizer.withoutAds(raw))
        val states = cleaned.getJSONObject("state_machine").getJSONArray("states")
        assertEquals(4, states.getJSONObject(0).getJSONObject("transitions").getJSONObject("advance").getInt("state_index"))
        assertTrue(states.getJSONObject(0).getJSONObject("transitions").isNull("skip_next"))
        assertEquals(4, states.getJSONObject(1).getJSONObject("transitions").getJSONObject("advance").getInt("state_index"))

        val prevTr = cleaned.getJSONObject("previous_state_machine")
            .getJSONArray("states")
            .getJSONObject(0)
            .getJSONObject("transitions")
        assertEquals(2, prevTr.getJSONObject("advance").getInt("state_index"))
        assertEquals(2, prevTr.getJSONObject("skip_next").getInt("state_index"))
    }

    @Test
    fun displayOnlyShowNextIsNeverFollowed() {
        val raw = """{"state_machine":{"tracks":[${songTrack("A")},${adTrack(0)},${songTrack("B")}],"states":[${state(0, advance = 1, skipNext = null)},${state(1, advance = null, skipNext = null, showNext = 2)},${state(2, advance = null, skipNext = null)}]}}"""

        val result = StateMachineSanitizer.withoutAds(raw)
        assertEquals(raw, result)
    }

    @Test
    fun cleanMusicTracksWithNullAdIdAreReturnedUntouched() {
        val raw = """{"state_machine":{"tracks":[${songTrack("A", ""","ad_id":null,"is_advertisement":false""")},${songTrack("B")}],"states":[${state(0, advance = 1, skipNext = 1)},${state(1, advance = null, skipNext = null)}]}}"""

        val result = StateMachineSanitizer.withoutAds(raw)
        assertEquals(raw, result)
    }

    @Test
    fun updatedStateRefOnAdAdvancesPastAdStateToPlayableTrack() {
        val raw = """
            {
              "state_machine": {
                "tracks": [${adTrack(0)}, ${adTrack(1)}, ${songTrack("Next")}],
                "states": [
                  ${state(0, advance = 1, skipNext = null)},
                  ${state(1, advance = 2, skipNext = 2)},
                  ${state(2, advance = null, skipNext = null)}
                ]
              },
              "updated_state_ref": {"state_index": 0, "paused": false}
            }
        """.trimIndent()

        val cleaned = JSONObject(StateMachineSanitizer.withoutAds(raw))
        assertEquals(2, cleaned.getJSONObject("updated_state_ref").getInt("state_index"))
    }
}
