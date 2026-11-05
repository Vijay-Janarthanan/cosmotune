package com.cosmotune.music.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.ui.components.SourceQuality
import com.cosmotune.ui.nowplaying.QualityPill
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class QualityPillPreviewTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun thePillsDrawOnADarkPlayer() {
        val scene = ImageComposeScene(560, 300, Density(2f)) {
            Column(Modifier.background(Color(0xFF15151C)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((tier, label) in listOf(
                    SourceQuality.HI_RES_LOSSLESS to "FLAC · 24-bit · 96.0 kHz",
                    SourceQuality.LOSSLESS to "FLAC · 16-bit · 44.1 kHz",
                    SourceQuality.HIGH_QUALITY to "AAC · 320 kbps · 44.1 kHz",
                    SourceQuality.DOLBY_ATMOS to "E-AC-3 JOC · 768 kbps · 48.0 kHz",
                )) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QualityPill(tier)
                        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                    }
                }
            }
        }
        var bytes: ByteArray? = null
        repeat(5) { bytes = scene.render(it * 50_000_000L).encodeToData(EncodedImageFormat.PNG)?.bytes }
        scene.close()
        File("build/tray-preview").mkdirs()
        File("build/tray-preview/quality-pills.png").writeBytes(bytes!!)
        assertTrue(bytes!!.size > 3_000)
    }
}
