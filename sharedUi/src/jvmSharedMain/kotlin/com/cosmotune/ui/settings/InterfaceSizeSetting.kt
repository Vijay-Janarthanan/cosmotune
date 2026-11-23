package com.cosmotune.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.settings.InterfaceSize
import com.cosmotune.settings.TextFont
import com.cosmotune.ui.components.CosmoIcons

/** The titles and lines of the two rows below, for the hosts' search. */
object InterfaceSizeText {
    const val SIZE = "Interface size"
    const val SIZE_LINE = "Draw the whole app smaller or larger: text, buttons and covers together."
    const val FONT = "Text font"
    const val FONT_LINE = "The font of lists, settings and lyrics. Headings keep Cosmotune's own."
}

/** Look and colours, Interface size: Compact, Default, Large or Larger, as a segmented choice under its row. */
@Composable
fun CosmoInterfaceSizeSetting(modifier: Modifier = Modifier) {
    val settings = rememberCosmoLookSettings()
    val size by settings.interfaceSize.collectAsState()
    Column(modifier.fillMaxWidth()) {
        CosmoSettingsRow(icon = CosmoIcons.Expand, title = InterfaceSizeText.SIZE, subtitle = InterfaceSizeText.SIZE_LINE)
        CosmoSettingsSegmented(
            options = InterfaceSize.entries.map { it.label },
            selectedIndex = InterfaceSize.entries.indexOf(size),
            onSelect = { settings.setInterfaceSize(InterfaceSize.entries[it]) },
            modifier = Modifier.padding(start = CosmoSettingsInsets.textStart, end = CosmoSettingsInsets.side, bottom = 8.dp),
            compact = true,
        )
    }
}

/** Look and colours, Text font: Cosmotune, System or Serif, as a segmented choice under its row. */
@Composable
fun CosmoTextFontSetting(modifier: Modifier = Modifier) {
    val settings = rememberCosmoLookSettings()
    val font by settings.textFont.collectAsState()
    Column(modifier.fillMaxWidth()) {
        CosmoSettingsRow(icon = CosmoIcons.Lyrics, title = InterfaceSizeText.FONT, subtitle = InterfaceSizeText.FONT_LINE)
        CosmoSettingsSegmented(
            options = TextFont.entries.map { it.label },
            selectedIndex = TextFont.entries.indexOf(font),
            onSelect = { settings.setTextFont(TextFont.entries[it]) },
            modifier = Modifier.padding(start = CosmoSettingsInsets.textStart, end = CosmoSettingsInsets.side, bottom = 8.dp),
            compact = true,
        )
    }
}
