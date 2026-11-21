package com.cosmotune.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import com.cosmotune.theme.OnePalette
import com.cosmotune.ui.theme.*

/**
 * The look Podio's pages are drawn in, without the app's shell: the One palette (dark), Cosmo's type and the Material bridge over
 * them, as `CosmoLookTheme` provides them in the app. Podio is built on the kit, which reads these.
 */
@Composable internal fun PodioTestTheme(content: @Composable () -> Unit) {
    val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
        .newInstance(CosmoPaletteState(OnePalette.of(true), true)) }
    val type = rememberCosmoType()
    MaterialTheme(colorScheme = OnePalette.of(true).toColorScheme(darkColorScheme()), typography = type.toMaterialTypography(MaterialTheme.typography)) {
        CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, content = content)
    }
}

/**
 * A tab of Podio's bottom bar or rail named [label] (the shared CosmoNavShell): a selectable whose words or merged content
 * description are the name, which a tab with a count carries as "Following, 2 new".
 */
internal fun podioTab(label: String): SemanticsMatcher = (hasText(label) or hasContentDescription(label) or
    SemanticsMatcher("content description starts \"$label, \"") { node ->
        val names: List<String> = node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { emptyList() }
        names.any { name -> name.startsWith("$label, ") }
    }) and isSelectable()
