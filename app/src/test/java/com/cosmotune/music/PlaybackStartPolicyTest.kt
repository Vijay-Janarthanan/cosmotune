package com.cosmotune.music
import com.cosmotune.audio.PlaybackStartPolicy
import com.cosmotune.music.data.settings.AppSettings
import com.cosmotune.music.data.sources.SourceStream
import com.cosmotune.music.data.sources.TrackMatcher
import com.cosmotune.music.playback.QualityUpgrade
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import org.junit.Assert.*
class PlaybackStartPolicyTest {
    @Test fun nonUpgradingPoliciesCancelLateInitialOffers() {
        val previous=AppSettings.playbackStartPolicy.value
        try {
            for(policy in listOf(PlaybackStartPolicy.NO_UPGRADE,PlaybackStartPolicy.BEST_QUALITY_FIRST)) {
                AppSettings.playbackStartPolicy.value=policy
                val late=CompletableDeferred<SourceStream?>()
                assertFalse(QualityUpgrade.settledForLess("policy-fixture",TrackMatcher.Target("A Song","Artist",180),late))
                assertTrue(late.isCancelled)
                assertFalse(QualityUpgrade.isPending("policy-fixture"))
            }
        } finally { AppSettings.playbackStartPolicy.value=previous }
    }
}
