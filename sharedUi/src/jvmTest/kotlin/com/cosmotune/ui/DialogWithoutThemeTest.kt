package com.cosmotune.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.cosmotune.HighlightIcon
import com.cosmotune.ReleaseHighlight
import com.cosmotune.ui.sheets.CosmoAlertDialog
import com.cosmotune.ui.whatsnew.WhatsNew
import com.cosmotune.ui.whatsnew.WhatsNewAction
import com.cosmotune.ui.whatsnew.WhatsNewDialog
import com.cosmotune.ui.whatsnew.WhatsNewSurface
import kotlin.test.Test

/**
 * A host can open an Orbit dialog before or outside its theme: the desktop's update offer at launch (1.0.21 crashed there with
 * "CosmoLookTheme is not installed" as soon as 1.0.22 was out), the TV's update card under its own root theme. Such a dialog draws
 * in the dark look instead of throwing.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DialogWithoutThemeTest {
    /** A default for a value of [type]: false, zero, an empty text, or null. */
    private fun defaultOf(type: java.lang.reflect.Type): Any? = when (type) {
        java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> false
        java.lang.Integer.TYPE, java.lang.Integer::class.java -> 0
        java.lang.Long.TYPE, java.lang.Long::class.java -> 0L
        java.lang.Float.TYPE, java.lang.Float::class.java -> 0f
        String::class.java -> ""
        else -> null
    }

    /** Answers every call of an interface with a default: flows of defaults, nested interfaces as more of the same. */
    private fun <T> fake(type: Class<T>): T = type.cast(java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
        when {
            method.name == "equals" -> proxy === args?.getOrNull(0)
            method.name == "hashCode" -> System.identityHashCode(proxy)
            method.name == "toString" -> "fake ${type.simpleName}"
            kotlinx.coroutines.flow.StateFlow::class.java.isAssignableFrom(method.returnType) -> {
                val generic = method.genericReturnType as? java.lang.reflect.ParameterizedType
                var argument = generic?.actualTypeArguments?.firstOrNull()
                if (argument is java.lang.reflect.WildcardType) argument = argument.upperBounds.first()
                kotlinx.coroutines.flow.MutableStateFlow(argument?.let(::defaultOf))
            }
            method.returnType.isInterface && method.returnType.name.startsWith("com.cosmotune") -> fake(method.returnType)
            else -> defaultOf(method.returnType)
        }
    })

    /** The player host the theme asks whether motion is reduced, faked with defaults; the previous host is put back. */
    private fun <T> withPlayerHost(block: () -> T): T {
        val field = com.cosmotune.music.ui.player.PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previous = field.get(null)
        com.cosmotune.music.ui.player.PlayerPlatform.install(fake(com.cosmotune.music.ui.player.PlayerHost::class.java))
        try { return block() } finally { field.set(null, previous) }
    }

    private fun drawWithoutTheme(content: @androidx.compose.runtime.Composable () -> Unit) = withPlayerHost {
        val scene = ImageComposeScene(width = 1200, height = 900, density = Density(1f), content = content)
        try {
            var nanos = 0L
            repeat(10) { scene.render(nanos); nanos += 16_000_000L }
        } finally {
            scene.close()
        }
    }

    private val card = WhatsNew(
        version = "9.9.9",
        highlights = listOf(ReleaseHighlight(HighlightIcon.LOOK, "A made-up change", "Invented text for a test.")),
        primary = WhatsNewAction("Update now") {},
        secondary = WhatsNewAction("Later") {},
        onFullNotes = {},
    )

    @Test fun desktopUpdateOfferDrawsWithoutTheme() = drawWithoutTheme { WhatsNewDialog(card, onDismiss = {}) }

    @Test fun tvUpdateCardDrawsWithoutTheme() = drawWithoutTheme { WhatsNewDialog(card, onDismiss = {}, surface = WhatsNewSurface.TV) }

    @Test fun alertDrawsWithoutTheme() = drawWithoutTheme {
        CosmoAlertDialog(onDismissRequest = {}, title = "A question", message = "Invented text.", confirmLabel = "OK", onConfirm = {})
    }
}
