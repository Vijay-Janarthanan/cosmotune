package com.cosmotune.ui.browse

import androidx.compose.runtime.Stable
import com.cosmotune.music.data.model.MoodGenre
import com.cosmotune.music.data.model.MoodGenreSection
import com.cosmotune.music.data.model.UiState

/**
 * Explore's moods and genres, handed to the new Home and Search by the roots (decision E4): the roots already hold the
 * Explore state, and these two screens only read it. Split it with `com.cosmotune.browse.BrowseSplit`.
 *
 * [onOpen] opens one mood or genre (the same destination as tapping it on Explore); [onRetry] loads Explore again after an error.
 */
@Stable
class CosmoBrowse(
    val state: UiState<List<MoodGenreSection>>,
    val onOpen: (MoodGenre) -> Unit,
    val onRetry: () -> Unit,
)
