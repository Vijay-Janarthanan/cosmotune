package com.cosmotune.ui.providers

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cosmotune.providers.ProviderSetting
import kotlinx.serialization.json.*

@Composable
internal fun ProviderSettingField(field: ProviderSetting, value: JsonElement, change: (JsonElement) -> Unit,
    busy: Boolean, onAction: ((ProviderSetting) -> Unit)? = null) {
    val title = field.label.ifBlank { field.key }
    val sensitive = field.secret || field.type in setOf("password", "secret", "token") ||
        Regex("token|secret|password|cookie|api.?key", RegexOption.IGNORE_CASE).containsMatchIn(field.key)
    when {
        field.type == "button" -> TextButton(enabled = !busy && field.action != null && onAction != null, onClick = { onAction?.invoke(field) }) { Text(title) }
        field.type in setOf("boolean", "bool", "checkbox") || (field.default as? JsonPrimitive)?.booleanOrNull != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f)); Switch((value as? JsonPrimitive)?.booleanOrNull == true, { change(JsonPrimitive(it)) }, enabled = !busy)
        }
        field.options.isNotEmpty() -> {
            var expanded by remember { mutableStateOf(false) }
            val selected = field.options.firstOrNull { option ->
                val raw = (option as? JsonObject)?.get("value") ?: option
                raw == value || (raw is JsonPrimitive && value is JsonPrimitive && raw.content == value.content)
            }
            val selectedLabel = ((selected as? JsonObject)?.get("label") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: (value as? JsonPrimitive)?.contentOrNull.orEmpty()
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = !busy) { Text("$title: ${if (sensitive) "••••" else selectedLabel}") }
                DropdownMenu(expanded, { expanded = false }) {
                    field.options.forEach { option ->
                        val raw = (option as? JsonObject)?.get("value") ?: option
                        val label = ((option as? JsonObject)?.get("label") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                            ?: if (sensitive) "••••" else (raw as? JsonPrimitive)?.contentOrNull ?: raw.toString()
                        DropdownMenuItem(text = { Text(label) }, onClick = { change(raw); expanded = false })
                    }
                }
            }
        }
        else -> OutlinedTextField((value as? JsonPrimitive)?.contentOrNull ?: if (value == JsonNull) "" else value.toString(), { text ->
            change(if (field.type in setOf("number", "integer")) text.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(text) else JsonPrimitive(text))
        }, label = { Text(title) }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (sensitive) PasswordVisualTransformation() else VisualTransformation.None)
    }
    if (field.description.isNotBlank()) Text(field.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
