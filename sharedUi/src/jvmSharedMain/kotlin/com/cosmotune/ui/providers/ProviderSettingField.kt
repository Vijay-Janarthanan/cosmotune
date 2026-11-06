package com.cosmotune.ui.providers

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.providers.ProviderSetting
import com.cosmotune.ui.orbit.OrbitFormField
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitMenuPill
import com.cosmotune.ui.orbit.OrbitSwitchRow
import com.cosmotune.ui.sheets.CosmoDialogQuietButton
import kotlinx.serialization.json.*

@Composable
internal fun ProviderSettingField(field: ProviderSetting, value: JsonElement, change: (JsonElement) -> Unit,
    busy: Boolean, onAction: ((ProviderSetting) -> Unit)? = null) {
    val title = field.label.ifBlank { field.key }
    val sensitive = field.secret || field.type in setOf("password", "secret", "token") ||
        Regex("token|secret|password|cookie|api.?key", RegexOption.IGNORE_CASE).containsMatchIn(field.key)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    when {
        field.type == "button" -> CosmoDialogQuietButton(title, { onAction?.invoke(field) }, enabled = !busy && field.action != null && onAction != null)
        field.type in setOf("boolean", "bool", "checkbox") || (field.default as? JsonPrimitive)?.booleanOrNull != null -> {
            val checked = (value as? JsonPrimitive)?.booleanOrNull == true
            OrbitSwitchRow(title, checked, { change(JsonPrimitive(it)) }, enabled = !busy)
        }
        field.options.isNotEmpty() -> {
            var expanded by remember { mutableStateOf(false) }
            val selected = field.options.firstOrNull { option ->
                val raw = (option as? JsonObject)?.get("value") ?: option
                raw == value || (raw is JsonPrimitive && value is JsonPrimitive && raw.content == value.content)
            }
            val selectedLabel = ((selected as? JsonObject)?.get("label") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: (value as? JsonPrimitive)?.contentOrNull.orEmpty()
            OrbitMenuPill("$title: ${if (sensitive) "••••" else selectedLabel}", expanded, onOpen = { expanded = true }, onDismiss = { expanded = false },
                items = field.options.map { option ->
                    val raw = (option as? JsonObject)?.get("value") ?: option
                    val label = ((option as? JsonObject)?.get("label") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: if (sensitive) "••••" else (raw as? JsonPrimitive)?.contentOrNull ?: raw.toString()
                    OrbitMenuItem(label, selected = option == selected) { change(raw); expanded = false }
                }, enabled = !busy, header = title)
        }
        else -> OrbitFormField((value as? JsonPrimitive)?.contentOrNull ?: if (value == JsonNull) "" else value.toString(), { text ->
            change(if (field.type in setOf("number", "integer")) text.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(text) else JsonPrimitive(text))
        }, placeholder = title, label = title, enabled = !busy, secret = sensitive)
    }
    if (field.description.isNotBlank()) ProviderNote(field.description)
    }
}
