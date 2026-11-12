package com.cosmotune.desktop.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.cosmotune.music.desktop.DesktopUpdateChecker
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.whatsnew.WhatsNewCard
import com.cosmotune.ui.whatsnew.WhatsNewSurface
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The update card's states and the top bar's Update pill drawn offscreen in both themes, under build/update-board, for the person
 * to look at before the wiring ships; and the card's words and actions per state, which are asserted.
 */
@OptIn(ExperimentalComposeUiApi::class)
class UpdateBoardRenderTest {
    private val out = File("build/update-board").apply { mkdirs() }
    private val notes = File("../docs/releases/1.0.22.md").takeIf { it.isFile }?.readText()
    private val info = DesktopUpdateChecker.UpdateInfo("1.0.23", "https://example.invalid", null, notes, downloadSize = 160_000_000)
    private val windows = DesktopInstall.WindowsInstalled(File("C:\\Cosmotune"), File("C:\\Cosmotune\\Cosmotune.exe"), true)
    private val actions = UpdateCardActions({}, {}, {}, {}, {})

    private fun card(step: UpdateStep, install: DesktopInstall = windows, oneClick: Boolean = true, failure: String? = null) =
        DesktopUpdateCard.of(info, step, install, 158_000_000, oneClick, "1.0.22", failure, actions)

    /** Answers every call of an interface with a default, for the player host the theme asks whether motion is reduced. */
    private fun <T> fake(type: Class<T>): T = type.cast(java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
        when {
            method.name == "equals" -> proxy === args?.getOrNull(0)
            method.name == "hashCode" -> System.identityHashCode(proxy)
            kotlinx.coroutines.flow.StateFlow::class.java.isAssignableFrom(method.returnType) -> kotlinx.coroutines.flow.MutableStateFlow(false)
            method.returnType.isInterface && method.returnType.name.startsWith("com.cosmotune") -> fake(method.returnType)
            method.returnType == java.lang.Boolean.TYPE -> false
            method.returnType == Integer.TYPE -> 0
            method.returnType == java.lang.Float.TYPE -> 0f
            method.returnType == java.lang.Long.TYPE -> 0L
            else -> null
        }
    })

    private fun shoot(name: String, dark: Boolean, width: Int, height: Int, content: @Composable () -> Unit) {
        com.cosmotune.music.ui.player.PlayerPlatform.install(fake(com.cosmotune.music.ui.player.PlayerHost::class.java))
        val scene = ImageComposeScene(width = width * 2, height = height * 2, density = Density(2f)) {
            com.cosmotune.ui.theme.CosmoLookTheme(dark = dark, legacy = { MaterialTheme { it() } }) {
                val colors = LocalCosmoColors.current
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.fillMaxSize().background(Color(colors.palette.backgroundTop)), contentAlignment = Alignment.Center) { content() }
                }
            }
        }
        try {
            var nanos = 0L
            var image = scene.render(nanos)
            repeat(30) { nanos += 16_000_000L; image = scene.render(nanos) }
            val file = File(out, "$name-${if (dark) "dark" else "light"}.png")
            file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            assertTrue(file.length() > 0)
        } finally {
            scene.close()
        }
    }

    @Test fun cardWordsPerState() {
        assertEquals("Update now", card(UpdateStep.Idle).primary.label)
        assertEquals("1.0.22 → 1.0.23 · 151 MB", card(UpdateStep.Idle).meta)
        assertEquals(
            "1.0.22 → 1.0.23 · 151 MB · Windows will ask for permission",
            card(UpdateStep.Idle, windows.copy(writable = false)).meta,
        )
        assertEquals("Cancel", card(UpdateStep.Downloading(0.4f)).primary.label)
        assertEquals("Cosmotune will close and open again", card(UpdateStep.Installing).meta)
        assertEquals("Try again", card(UpdateStep.Failed("Connection reset")).primary.label)
        assertEquals("Download", card(UpdateStep.Idle, DesktopInstall.WindowsPortable(File("D:\\C")), oneClick = false).primary.label)
        assertEquals(
            "Move Cosmotune to Applications to update in one click",
            card(UpdateStep.Idle, DesktopInstall.Browser("Move Cosmotune to Applications to update in one click"), oneClick = false).meta,
        )
        val after = card(UpdateStep.Idle, failure = "The update didn't install. Cosmotune is still on 1.0.22.")
        assertEquals("The update didn't install. Cosmotune is still on 1.0.22.", after.problem)
    }

    @Test fun board() {
        val states = listOf(
            "1-offer" to card(UpdateStep.Idle),
            "2-downloading" to card(UpdateStep.Downloading(0.42f)),
            "3-installing" to card(UpdateStep.Installing),
            "4-download-failed" to card(UpdateStep.Failed("The connection dropped. Check your internet and try again.")),
            "5-download-page" to card(UpdateStep.Idle, DesktopInstall.WindowsPortable(File("D:\\C")), oneClick = false),
            "6-install-failed" to card(UpdateStep.Idle, failure = "The update didn't install. Cosmotune is still on 1.0.22."),
        )
        for (dark in listOf(true, false)) {
            states.forEach { (name, whatsNew) -> shoot("card-$name", dark, 1100, 760) { WhatsNewCard(whatsNew, {}, WhatsNewSurface.DESKTOP) } }
            shoot("top-bar-pill", dark, 240, 80) { UpdatePill({}) }
        }
    }
}
