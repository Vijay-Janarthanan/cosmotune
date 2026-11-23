package com.cosmotune.reading.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.ui.ReadChip
import com.cosmotune.manga.ui.ReadChipRow
import com.cosmotune.manga.ui.ReadOverline
import com.cosmotune.manga.ui.ReadType
import com.cosmotune.manga.ui.ReadSheetSection
import com.cosmotune.reading.data.ReadingPreferences
import com.cosmotune.ui.collectAsUiState
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.rememberUiCoroutineScope
import com.cosmotune.ui.components.CosmoIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Settings › Read (board frame 15), which used to be only a way into the mode: the reader's look for every book (page colour,
 * typeface, size, spacing, margins, layout, brightness, keep screen on; the same sheet the reader opens) and the connected
 * libraries (OPDS servers), which Browse lists under "My libraries".
 */
@Composable
fun ReadSettingsSection(modifier: Modifier = Modifier) {
    val repository = rememberReadingRepository().getOrNull()
    val look = orbitLook()
    if (repository == null) {
        Text("Your reading library could not be opened. Check storage access and try again.", style = ReadType.body(), color = look.dim, modifier = modifier.padding(16.dp))
        return
    }
    val state by repository.state.collectAsUiState()
    val scope = rememberUiCoroutineScope()
    var connecting by remember { mutableStateOf(false) }
    var syncSheet by remember { mutableStateOf(false) }
    var koSheet by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val addDictionary = rememberReadingImport(ReadingPick.DICTIONARY) { name, open -> scope.launch {
        try { repository.importDictionary(name, open); error = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "The dictionary could not be added." }
    } }
    val addFont = rememberReadingImport(ReadingPick.FONT) { name, open -> scope.launch {
        try { val font = repository.importFont(name, open); repository.updatePreferences(repository.state.value.preferences.copy(fontFamily = "font:${font.id}")); error = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "The font could not be added." }
    } }
    fun update(value: ReadingPreferences) = scope.launch {
        try { repository.updatePreferences(value); error = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Reader settings could not be saved." }
    }
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("settings:read")) {
        error?.let { Text(it, style = ReadType.body(), color = look.accent, modifier = Modifier.padding(horizontal = 20.dp)) }
        ReadOverline("Reader", inset = 16.dp)
        ReadingAppearanceChoices(state.preferences, reflowable = true, onChange = ::update, fonts = state.fonts, onAddFont = addFont,
            onRemoveFont = { id -> scope.launch { try { repository.removeFont(id) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { error = "The font could not be removed." } } })
        ReadSheetSection("Your own style") {
            var css by remember(state.preferences.customCss) { mutableStateOf(state.preferences.customCss) }
            com.cosmotune.ui.orbit.OrbitInputField(css, { css = it.take(4_000) }, "CSS laid over every book, such as p { letter-spacing: .02em }",
                modifier = Modifier.fillMaxWidth().testTag("settings:read:css"), singleLine = false, minLines = 3)
            if (css != state.preferences.customCss) ReadChipRow(Modifier.padding(top = 8.dp), inset = 0.dp) {
                ReadChip("Save style", false, { update(state.preferences.copy(customCss = css)) }, Modifier.testTag("settings:read:css:save"), icon = CosmoIcons.Check)
            }
        }
        ReadOverline("Connected libraries", inset = 16.dp)
        Text(if (state.sources.isEmpty()) "Connect a library's OPDS address to browse and borrow from it in Browse." else state.sources.joinToString(" · ") { it.name },
            style = ReadType.body(), color = look.dim, modifier = Modifier.padding(horizontal = 20.dp))
        ReadChipRow(Modifier.padding(top = 8.dp)) {
            ReadChip(if (state.sources.isEmpty()) "Connect library" else "Manage libraries", false, { connecting = true }, Modifier.testTag("settings:read:libraries"), icon = CosmoIcons.Plus)
        }
        ReadOverline("Book folders", inset = 16.dp)
        Text(if (state.folders.isEmpty()) "Add a folder from Library's + to have its books added, and new ones each time Read opens."
            else state.folders.joinToString(" · ") { it.name }, style = ReadType.body(), color = look.dim, modifier = Modifier.padding(horizontal = 20.dp))
        if (state.folders.isNotEmpty()) ReadChipRow(Modifier.padding(top = 8.dp)) {
            state.folders.forEach { folder ->
                ReadChip("Stop watching ${folder.name}", false, { scope.launch { repository.removeFolder(folder.location) } }, icon = CosmoIcons.Close)
            }
        }
        ReadOverline("Sync across devices", inset = 16.dp)
        val sync = state.webDav
        Text(if (sync == null) "Keep where you are, bookmarks, highlights and notes the same on every device through your own WebDAV folder (Nextcloud, a NAS…)."
            else listOf(sync.url, sync.lastResult.ifBlank { "Not synced yet" } + if (sync.lastSyncMs > 0) " · " + com.cosmotune.manga.ui.agoLabel(sync.lastSyncMs) else "").joinToString("\n"),
            style = ReadType.body(), color = look.dim, modifier = Modifier.padding(horizontal = 20.dp))
        ReadChipRow(Modifier.padding(top = 8.dp)) {
            if (sync == null) ReadChip("Connect a folder", false, { syncSheet = true }, Modifier.testTag("settings:read:sync"), icon = CosmoIcons.Plus)
            else {
                ReadChip("Sync now", false, { scope.launch { repository.syncWebDav() } }, Modifier.testTag("settings:read:sync-now"), icon = CosmoIcons.Repeat)
                ReadChip("Disconnect", false, { scope.launch { repository.disconnectWebDav() } }, icon = CosmoIcons.Close)
            }
        }
        ReadOverline("KOReader sync", inset = 16.dp)
        val ko = state.koSync
        Text(if (ko == null) "Share where you are in a book with KOReader, Kobo and other KOReader-sync apps, on their free server or your own."
            else "${ko.username} · ${ko.server.removePrefix("https://").trimEnd('/')}" + (ko.lastResult.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""),
            style = ReadType.body(), color = look.dim, modifier = Modifier.padding(horizontal = 20.dp))
        ReadChipRow(Modifier.padding(top = 8.dp)) {
            if (ko == null) ReadChip("Sign in", false, { koSheet = true }, Modifier.testTag("settings:read:kosync"), icon = CosmoIcons.User)
            else ReadChip("Sign out", false, { scope.launch { repository.disconnectKoSync() } }, icon = CosmoIcons.Close)
        }
        ReadOverline("Dictionaries", inset = 16.dp)
        Text(if (state.dictionaries.isEmpty()) "Add a StarDict dictionary (a ZIP or .tar.gz) to look words up without a connection."
            else state.dictionaries.joinToString(" · ") { "${it.name} (${it.words} words)" },
            style = ReadType.body(), color = look.dim, modifier = Modifier.padding(horizontal = 20.dp))
        ReadChipRow(Modifier.padding(top = 8.dp)) {
            ReadChip("Add dictionary", false, { addDictionary() }, Modifier.testTag("settings:read:add-dictionary"), icon = CosmoIcons.Plus)
            state.dictionaries.forEach { dictionary ->
                ReadChip("Remove ${dictionary.name}", false, { scope.launch {
                    try { repository.removeDictionary(dictionary.id) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { error = "The dictionary could not be removed." }
                } }, icon = CosmoIcons.Close)
            }
        }
        com.cosmotune.ui.orbit.OrbitSwitchRow("Look words up online", state.preferences.onlineLookup, { update(state.preferences.copy(onlineLookup = it)) },
            Modifier.padding(horizontal = 20.dp, vertical = 6.dp), switchModifier = Modifier.testTag("settings:read:online-lookup"))
        Text("Wiktionary, Wikipedia and the MyMemory translator get only the words you look up.", style = ReadType.caption(), color = look.dim,
            modifier = Modifier.padding(horizontal = 20.dp))
        if (state.preferences.onlineLookup) {
            ReadSheetSection("Translate into") {
                ReadChipRow(inset = 0.dp) {
                    ReadChip("This device's language", state.preferences.translateTo.isBlank(), { update(state.preferences.copy(translateTo = "")) })
                    translateTargets.forEach { code -> ReadChip(languageName(code), state.preferences.translateTo == code, { update(state.preferences.copy(translateTo = code)) }) }
                }
            }
        }
    }
    if (connecting) BookConnections(repository, onDismiss = { connecting = false }, onError = { error = it })
    if (syncSheet) ReadSyncSheet(repository, onDismiss = { syncSheet = false })
    if (koSheet) ReadKoSyncSheet(repository, onDismiss = { koSheet = false })
}

/** Connecting the WebDAV folder books sync through: its address, the user name and the password (an app password is best). */
@Composable
private fun ReadSyncSheet(repository: com.cosmotune.reading.data.ReadingRepository, onDismiss: () -> Unit) {
    val scope = rememberUiCoroutineScope()
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val look = orbitLook()
    com.cosmotune.ui.kit.KitSheet(onDismiss = onDismiss) {
        com.cosmotune.manga.ui.ReadSheetHeader("Sync folder", onDone = onDismiss, doneLabel = "Close")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Folder address", style = ReadType.caption(), color = look.dim)
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            com.cosmotune.ui.orbit.OrbitInputField(url, { url = it.take(500) }, "https://cloud.example.com/remote.php/dav/files/me/Books", modifier = Modifier.fillMaxWidth().testTag("settings:read:sync:url"))
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
            Text("User name", style = ReadType.caption(), color = look.dim)
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            com.cosmotune.ui.orbit.OrbitInputField(user, { user = it.take(200) }, "User name", modifier = Modifier.fillMaxWidth())
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
            Text("Password", style = ReadType.caption(), color = look.dim)
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            com.cosmotune.ui.orbit.OrbitInputField(password, { password = it.take(500) }, "An app password", modifier = Modifier.fillMaxWidth(), secret = true)
            message?.let { Text(it, style = ReadType.body(), color = look.dim, modifier = Modifier.padding(top = 12.dp)) }
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
            com.cosmotune.manga.ui.ReadButton(if (busy) "Connecting…" else "Connect and sync", {
                busy = true; message = null
                scope.launch {
                    try { val result = repository.connectWebDav(url, user, password); message = result; if (result == "Synced") onDismiss() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { message = failure.message ?: "This folder could not be used." }
                    finally { busy = false }
                }
            }, enabled = !busy && url.isNotBlank(), modifier = Modifier.testTag("settings:read:sync:connect"))
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        }
    }
}

/** Signing in to KOReader sync: the server (the free one unless changed), the user name and the password. */
@Composable
private fun ReadKoSyncSheet(repository: com.cosmotune.reading.data.ReadingRepository, onDismiss: () -> Unit) {
    val scope = rememberUiCoroutineScope()
    var server by remember { mutableStateOf(com.cosmotune.reading.data.ReadingKoSyncClient.DEFAULT_SERVER) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val look = orbitLook()
    fun go(create: Boolean) {
        busy = true; message = null
        scope.launch {
            try { repository.connectKoSync(server, user, password, create); onDismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message ?: "Could not sign in." }
            finally { busy = false }
        }
    }
    com.cosmotune.ui.kit.KitSheet(onDismiss = onDismiss) {
        com.cosmotune.manga.ui.ReadSheetHeader("KOReader sync", onDone = onDismiss, doneLabel = "Close")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Server", style = ReadType.caption(), color = look.dim)
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            com.cosmotune.ui.orbit.OrbitInputField(server, { server = it.take(300) }, "https://sync.koreader.rocks", modifier = Modifier.fillMaxWidth())
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
            Text("User name", style = ReadType.caption(), color = look.dim)
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            com.cosmotune.ui.orbit.OrbitInputField(user, { user = it.take(100) }, "User name", modifier = Modifier.fillMaxWidth().testTag("settings:read:kosync:user"))
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
            Text("Password", style = ReadType.caption(), color = look.dim)
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            com.cosmotune.ui.orbit.OrbitInputField(password, { password = it.take(200) }, "Password", modifier = Modifier.fillMaxWidth(), secret = true)
            message?.let { Text(it, style = ReadType.body(), color = look.dim, modifier = Modifier.padding(top = 12.dp)) }
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
            ReadChipRow(inset = 0.dp) {
                ReadChip(if (busy) "Signing in…" else "Sign in", false, { if (!busy) go(false) }, Modifier.testTag("settings:read:kosync:sign-in"), icon = CosmoIcons.Check)
                ReadChip("Make an account", false, { if (!busy) go(true) }, icon = CosmoIcons.Plus)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        }
    }
}
