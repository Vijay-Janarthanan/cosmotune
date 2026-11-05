package com.cosmotune.music.data.search

import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchResult

/**
 * YouTube Music as one source of the "All" search: its song results through the anonymous search endpoint (the default of
 * [YtMusicRepository.search]), so a submission here is not written to the account's search history and carries none of its
 * cookies. Choosing YouTube Music alone in the picker goes to the full YouTube Music results page instead.
 */
fun youTubeMusicSearchSource(name: String = "YouTube Music"): AddonSearchSource =
    AddonSearchSource(name = name, id = AddonSearch.YOUTUBE) { query, limit ->
        YtMusicRepository.search(query, SearchFilter.SONGS).getOrThrow()
            .mapNotNull { row ->
                when (row) {
                    is SearchResult.TopTrack -> row.song
                    is SearchResult.Track -> row.song
                    else -> null
                }
            }
            .distinctBy { it.videoId }
            .take(limit)
    }
