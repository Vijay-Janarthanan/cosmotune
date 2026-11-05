package com.cosmotune.ui.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.cosmotune.providers.ProviderSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** The HTTP protocol sends these values as query parameters; package settings use the same form widgets. */
@Composable
fun HttpAddonSettingsEntry(title: String, values: Map<String, String>, enabled: Boolean = true, load: suspend () -> List<ProviderSetting>,
    onSave: (Map<String, String>) -> Unit,
    launcher: (@Composable (open: () -> Unit, enabled: Boolean) -> Unit)? = null) {
    var open by remember(title) { mutableStateOf(false) }
    if (launcher == null) TextButton(onClick = { open = true }, enabled = enabled) { Text("Source settings") }
    else launcher({ open = true }, enabled)
    if (!open) return
    val scope = rememberCoroutineScope()
    var schema by remember { mutableStateOf<List<ProviderSetting>?>(null) }
    var fields by remember { mutableStateOf(JsonObject(emptyMap())) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        loading = true; failed = false; saveFailed = false
        try {
            val declarations = load().also { list ->
                require(list.size <= 100 && list.map { it.key }.distinct().size == list.size)
                require(list.all { it.key.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,99}")) && it.options.size <= 100 })
            }
            fields = buildJsonObject { declarations.forEach { field -> put(field.key, values[field.key]?.let(::JsonPrimitive) ?: field.default) } }
            schema = declarations
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    val valid = schema?.filter { it.type in setOf("number", "integer") }?.all { field ->
        fields[field.key] == JsonNull || (fields[field.key] as? JsonPrimitive)?.doubleOrNull?.let { it.isFinite() && (field.type != "integer" || it % 1.0 == 0.0) } == true
    } == true
    AlertDialog(onDismissRequest = { open = false }, title = { Text(title) }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) item { LinearProgressIndicator() }
            if (failed) item {
                Text("The source settings could not be loaded. Check its connection and retry.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { attempt++ }) { Text("Retry") }
            }
            if (schema?.isEmpty() == true) item { Text("This source does not declare configurable settings.") }
            if (saveFailed) item { Text("The settings could not be saved. Your edits are still here.", color = MaterialTheme.colorScheme.error) }
            items(schema.orEmpty(), key = { it.key }) { field ->
                ProviderSettingField(field, fields[field.key] ?: field.default,
                    change = { fields = JsonObject(fields + (field.key to it)) }, busy = loading || saving)
            }
        }
    }, confirmButton = {
        TextButton(enabled = !loading && !saving && !failed && valid, onClick = {
            if (saving) return@TextButton
            saving = true; saveFailed = false
            scope.launch {
                try {
                    val selected = fields.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.takeUnless { it == JsonNull }?.contentOrNull?.let { key to it } }.toMap()
                    require(selected.size <= 100 && selected.entries.sumOf { it.key.length + it.value.length } <= 65_536)
                    onSave(selected); open = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { saveFailed = true }
                finally { saving = false }
            }
        }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } })
}
