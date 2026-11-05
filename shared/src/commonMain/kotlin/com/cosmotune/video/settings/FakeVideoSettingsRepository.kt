package com.cosmotune.video.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Settings in memory (plan task Y-2): starts at [initial] (the defaults of spec 5.11) and applies every update. */
class FakeVideoSettingsRepository(initial: VideoSettings = VideoSettings()) : VideoSettingsRepository {
    private val flow = MutableStateFlow(initial)

    override val settings: StateFlow<VideoSettings> = flow.asStateFlow()

    override fun update(transform: (VideoSettings) -> VideoSettings) = flow.update(transform)
}
