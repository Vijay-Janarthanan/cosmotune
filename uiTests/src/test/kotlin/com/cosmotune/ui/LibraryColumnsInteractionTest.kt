package com.cosmotune.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cosmotune.audio.LibraryAudioAnalysis
import com.cosmotune.listening.LibraryViewOptions
import com.cosmotune.music.data.model.Song
import com.cosmotune.ui.listening.LibraryColumnsBar
import com.cosmotune.ui.listening.LibraryColumnsLine
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The shared library column and filter bar, driven the way both hosts' song lists use it. */
@OptIn(ExperimentalTestApi::class)
class LibraryColumnsInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val lossless = Song("local:a", "Kannalane", "A. R. Rahman", null, albumName = "Bombay", localPath = "/m/a.flac")
    private val lossy = Song("local:b", "Humma", "A. R. Rahman", null, albumName = "Bombay", localPath = "/m/b.mp3")
    private val analysis = mapOf(
        lossless.videoId to LibraryAudioAnalysis(lossless.videoId, "a", integratedLufs = -12.3, codec = "flac", sampleRate = 44_100, genre = "Soundtrack"),
        lossy.videoId to LibraryAudioAnalysis(lossy.videoId, "b", integratedLufs = -9.0, codec = "mp3", sampleRate = 44_100, genre = "Film"),
    )

    @Test fun columnsAndQualityFilterChangeTheListAndAreSavedAsOneChoice() {
        var saved: String? = null
        compose.setContent {
            MaterialTheme {
                var options by remember { mutableStateOf(LibraryViewOptions()) }
                val lookup = { song: Song -> analysis[song.videoId] }
                Column {
                    LibraryColumnsBar(options, LibraryViewOptions.genresOf(listOf(lossless, lossy), lookup), onChange = { options = it; saved = it.encode() })
                    options.apply(listOf(lossless, lossy), lookup).forEach { song ->
                        Text(song.title)
                        LibraryColumnsLine(song, options, analysis[song.videoId])
                    }
                }
            }
        }
        compose.onNodeWithText("Humma").assertExists()
        compose.onNodeWithTag("library:columns:local:a").assertTextEquals("Bombay")

        compose.onNodeWithTag("library:columns:menu").performClick()
        compose.onNodeWithTag("library:column:QUALITY").performClick()
        compose.onNodeWithTag("library:columns:menu").performClick()
        compose.onNodeWithTag("library:column:LOUDNESS").performClick()
        compose.onNodeWithTag("library:columns:local:a").assertTextEquals("Bombay  ·  Lossless  ·  -12.3 LUFS")
        compose.onNodeWithTag("library:columns:local:b").assertTextEquals("Bombay  ·  Lossy  ·  -9.0 LUFS")

        compose.onNodeWithTag("library:filter:quality").performClick()
        compose.onNodeWithTag("library:quality:LOSSLESS").performClick()
        compose.onNodeWithText("Humma").assertDoesNotExist()
        compose.onNodeWithText("Kannalane").assertExists()
        assertEquals(LibraryViewOptions.decode(saved).quality.name, "LOSSLESS")

        compose.onNodeWithTag("library:filter:clear").performClick()
        compose.onNodeWithText("Humma").assertExists()
        compose.onNodeWithTag("library:filter:genre").performClick()
        compose.onNodeWithText("Film").performClick()
        compose.onNodeWithText("Kannalane").assertDoesNotExist()
        assertEquals("Film", LibraryViewOptions.decode(saved).genre)
    }
}
