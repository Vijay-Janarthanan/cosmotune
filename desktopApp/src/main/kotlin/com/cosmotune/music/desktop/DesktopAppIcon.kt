package com.cosmotune.music.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import com.cosmotune.settings.AppIcon
import cosmotune.desktopapp.generated.resources.Res
import cosmotune.desktopapp.generated.resources.logo
import org.jetbrains.compose.resources.decodeToSvgPainter
import org.jetbrains.compose.resources.painterResource

/** The window, taskbar and tray icon in the colours chosen in Look and colours, App icon: `logo.svg` recoloured. */
object DesktopAppIcon {

    /** The logo's SVG in [icon]'s colours, for the tray. */
    suspend fun svg(icon: AppIcon): ByteArray =
        icon.recolourSvg(Res.readBytes(LOGO).decodeToString()).encodeToByteArray()

    /** The window's icon, following the choice live. */
    @Composable
    fun painter(): Painter {
        val icon by com.cosmotune.ui.settings.rememberCosmoLookSettings().appIcon.collectAsState()
        val density = LocalDensity.current
        val plain = painterResource(Res.drawable.logo)
        val recoloured by produceState<Painter?>(null, icon, density) {
            value = if (icon == AppIcon.COSMOTUNE) null else runCatching { svg(icon).decodeToSvgPainter(density) }.getOrNull()
        }
        return recoloured ?: plain
    }

    private const val LOGO = "drawable/logo.svg"
}
