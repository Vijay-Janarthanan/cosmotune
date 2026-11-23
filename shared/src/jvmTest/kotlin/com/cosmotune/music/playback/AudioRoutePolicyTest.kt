package com.cosmotune.music.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioRoutePolicyTest {
    @Test fun aNewOutputNeverStartsOrChangesPlayback() {
        for (playing in listOf(true, false)) {
            assertEquals(RouteDecision(RouteAction.NONE), AudioRoutePolicy.decide(RouteChange.OUTPUT_ADDED, playing))
        }
    }

    @Test fun playbackPausesWhenItsOutputGoesAway() {
        assertEquals(RouteAction.PAUSE, AudioRoutePolicy.decide(RouteChange.OUTPUT_REMOVED, playing = true).action)
    }

    @Test fun pausedPlaybackStaysPausedWhenAnOutputGoesAway() {
        assertEquals(RouteAction.NONE, AudioRoutePolicy.decide(RouteChange.OUTPUT_REMOVED, playing = false).action)
    }

    @Test fun aChosenDeviceThatVanishedFallsBackToTheDefaultAndNeverResumes() {
        val playing = AudioRoutePolicy.decide(RouteChange.CHOSEN_OUTPUT_GONE, playing = true)
        assertTrue(playing.fallBackToDefault)
        assertEquals(RouteAction.PAUSE, playing.action)
        val paused = AudioRoutePolicy.decide(RouteChange.CHOSEN_OUTPUT_GONE, playing = false)
        assertTrue(paused.fallBackToDefault)
        assertEquals(RouteAction.NONE, paused.action)
    }

    @Test fun noDecisionEverAsksToPlay() {
        for (change in RouteChange.entries) for (playing in listOf(true, false)) {
            assertFalse(AudioRoutePolicy.decide(change, playing).action.name == "PLAY")
        }
    }
}
