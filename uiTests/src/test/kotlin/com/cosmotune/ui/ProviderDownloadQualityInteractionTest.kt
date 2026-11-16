package com.cosmotune.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cosmotune.music.data.downloads.ProviderDownloadChoice
import com.cosmotune.music.data.model.Song
import com.cosmotune.providers.ProviderHub
import com.cosmotune.ui.providers.rememberProviderDownloadQualityPicker
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderDownloadQualityInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun manifestSelectionRetainsProviderAndExactQualityAndCancelQueuesNothing() {
        runBlocking {
            ProviderHub.configure(temporary.newFolder(), force = true, cache = temporary.newFolder())
            withTimeout(10_000) { while (ProviderHub.state.value.loading) delay(20) }
            val manager = requireNotNull(ProviderHub.state.value.manager)
            val archive = ByteArrayOutputStream().use { output ->
                ZipOutputStream(output).use { zip ->
                    val files = listOf("manifest.json" to """{"name":"qualityfixture","version":"1.0.0","displayName":"Fixture","type":["download_provider"],"qualityOptions":[{"id":"FLAC_24","label":"Studio FLAC"},{"id":"AAC_96","label":"Small AAC"}]}""",
                        "index.js" to "registerExtension({getDownloadUrl:function(){return null;}});")
                    files.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
                }
                output.toByteArray()
            }
            manager.trust(manager.inspect { archive.inputStream() })
        }
        val track = Song("src:sflx-qualityfixture::exact-recording", "Song", "Artist", null)
        val selections = mutableListOf<Pair<Song, ProviderDownloadChoice?>>()
        compose.setContent { PodioTestTheme {
            val choose = rememberProviderDownloadQualityPicker { song, choice -> selections += song to choice }
            Button(onClick = { assertTrue(choose(track)) }) { Text("Download fixture") }
        } }
        compose.onNodeWithText("Download fixture").performClick()
        compose.onNodeWithText("Studio FLAC").performClick()
        compose.runOnIdle {
            assertEquals(track, selections.single().first)
            assertEquals(ProviderDownloadChoice("qualityfixture", "FLAC_24"), selections.single().second)
        }
        compose.onNodeWithText("Download fixture").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, selections.size) }
        compose.onNodeWithText("Download fixture").performClick()
        compose.onNodeWithText("Use download settings").performClick()
        compose.runOnIdle { assertEquals(track, selections.last().first); assertNull(selections.last().second) }
    }
}
