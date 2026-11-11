package com.cosmotune.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import com.cosmotune.ui.components.cosmoFocusRing
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.OrbitSwitch
import com.cosmotune.ui.orbit.PEACH
import com.cosmotune.ui.orbit.WATCH_ADD_FILL
import com.cosmotune.ui.orbit.WATCH_ADD_INK
import com.cosmotune.ui.orbit.WATCH_LIVE
import com.cosmotune.ui.orbit.WATCH_LIVE_INK
import com.cosmotune.ui.orbit.WatchBadge
import com.cosmotune.ui.orbit.WatchCardChip
import com.cosmotune.ui.orbit.WatchLogoTile
import com.cosmotune.ui.orbit.WatchPluginCard
import com.cosmotune.ui.orbit.WatchRoundButton
import com.cosmotune.ui.orbit.WatchRowFrame
import com.cosmotune.ui.orbit.logoFill
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.watch.InstallPreview
import com.cosmotune.watch.MetadataSource
import com.cosmotune.watch.PluginPackItem
import com.cosmotune.watch.WatchRepository
import com.cosmotune.watch.plugin.AddSourceResult
import com.cosmotune.watch.plugin.PluginInfo
import com.cosmotune.watch.plugin.UserSourceSpec
import kotlinx.coroutines.launch

/**
 * Plugins (board section 5): every built-in plugin with its switch (the free ones start on, so Watch plays from the first open),
 * your own servers, playlists and add-ons under "Yours" with the indigo "Add a plugin or source" card, sharing and importing a
 * plugin pack (a link and its QR code), and "Find plugins": directories that open in the browser, nothing fetched from them.
 */
@Composable
internal fun WatchPluginsPage(fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame) {
    val repository = watchRepository()
    val scope = rememberCoroutineScope()
    val plugins by repository.plugins.collectAsState()
    val metadata by repository.metadataSource.collectAsState()
    val builtIn = plugins.filter { it.builtIn }
    val yours = plugins.filter { !it.builtIn }
    var adding by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf<PluginInfo?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importLink by remember { mutableStateOf<String?>(null) }
    val side = frame.side

    val builtInColumn: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OrbitLabel("Built in · switch on or off", fonts, look.dim, Modifier.padding(horizontal = 6.dp))
            builtIn.forEach { p -> PluginRow(p, look, fonts, onToggle = { on -> scope.launch { repository.setPluginEnabled(p.id, on) } }) }
        }
    }
    val yoursColumn: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OrbitLabel("Yours", fonts, look.dim, Modifier.padding(horizontal = 6.dp))
            if (yours.isEmpty()) Text("Servers, playlists and add-ons you add show here.", style = fonts.body, color = look.dim, modifier = Modifier.padding(horizontal = 6.dp))
            yours.forEachIndexed { i, p ->
                PluginRow(p, look, fonts, onToggle = { on -> scope.launch { repository.setPluginEnabled(p.id, on) } },
                    menu = listOfNotNull(
                        OrbitMenuItem("Move up", "Its copies rank higher", enabled = i > 0) { scope.launch { repository.moveUserSource(p.id, i - 1) } },
                        OrbitMenuItem("Move down", null, enabled = i < yours.lastIndex) { scope.launch { repository.moveUserSource(p.id, i + 1) } },
                        if (p.kind == com.cosmotune.watch.plugin.PluginKind.NUVIO_SCRIPT) OrbitMenuItem("Check for update", "Compare with its source before installing") { updating = p } else null,
                        OrbitMenuItem("Remove", "Forget it and its sign-in", CosmoIcons.Close) { scope.launch { repository.removeUserSource(p.id) } },
                    ))
            }
            AddCard(fonts, look, Modifier.padding(top = 6.dp)) { adding = true }
            PackCard(fonts, look, onShare = { sharing = true }, onImport = { importing = true })
            LocalWatchRoomPacks.current?.let { packs -> RoomPacksCard(packs, repository, fonts, look, onImport = { importLink = it }) }
            LocalWatchLinkHandler.current?.let { handler -> LinkHandlerCard(handler, fonts, look) }
            if (metadata == MetadataSource.FALLBACK) {
                Text("No TMDB key is set, so pages come from Cinemeta and your plugins.", style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = frame.padding) {
            frame.header?.let { header -> item(key = "plugins:header") { header() } }
            item(key = "plugins:heading") {
                OrbitPageHeading("Plugins", "${builtIn.size} built in · ${builtIn.count { it.enabled }} on · plays from the start", fonts, look,
                    Modifier.padding(start = side + 6.dp, end = side, top = if (frame.wide) 24.dp else 18.dp))
            }
            // Where to find more, first and plain to see: the directories open in the browser; a link found there is added below.
            item(key = "plugins:find") {
                FindPlugins(repository, fonts, look, frame.wide, Modifier.padding(horizontal = side).padding(top = if (frame.wide) 22.dp else 18.dp))
            }
            item(key = "plugins:body") {
                if (frame.wide) {
                    Row(Modifier.padding(horizontal = side).padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Box(Modifier.weight(1f).widthIn(max = 560.dp)) { builtInColumn() }
                        Box(Modifier.weight(1f)) { yoursColumn() }
                    }
                } else {
                    Column(Modifier.padding(horizontal = side).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) { builtInColumn(); yoursColumn() }
                }
            }
            if (metadata == MetadataSource.TMDB) item(key = "plugins:tmdb") {
                TmdbNotice(fonts, look, Modifier.padding(horizontal = side + 6.dp).padding(top = 28.dp))
            }
            item(key = "plugins:end") { Spacer(Modifier.height(40.dp)) }
        }
    }
    if (adding) AddSourceDialog(repository, fonts, look) { adding = false }
    updating?.let { plugin -> ScriptUpdateDialog(plugin, repository, fonts, look) { updating = null } }
    if (sharing) SharePackDialog(repository, fonts, look) { sharing = false }
    if (importing) ImportPackDialog(repository, fonts, look) { importing = false }
    importLink?.let { link -> ImportPackDialog(repository, fonts, look, initialLink = link) { importLink = null } }
}

/**
 * In a room: offer this member's add-ons to the others as a chat message ([com.cosmotune.watch.WatchRoom.packOffer], add-on
 * links only), and the packs the others offered, each with Import (which shows what it holds before adding anything).
 */
@Composable
private fun RoomPacksCard(packs: WatchRoomPacks, repository: WatchRepository, fonts: OrbitFonts, look: OrbitLook, onImport: (String) -> Unit) {
    val inRoom by packs.inRoom.collectAsState()
    if (!inRoom) return
    val offers by packs.offers.collectAsState()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(look.row).padding(18.dp)) {
        Text("In this room", style = fonts.title, color = look.ink)
        Text("Offer your add-ons so the others can play the same copies. Only add-on links go in the chat.", style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim,
            modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WatchCardChip("Offer my add-ons", look.ink, fonts, icon = CosmoIcons.Together, onClick = {
                scope.launch {
                    val link = runCatching { repository.exportPluginPack() }.getOrNull()
                    val message = link?.let { com.cosmotune.watch.WatchRoom.packOffer(it, packs.selfName()) }
                    note = when {
                        link == null -> "Could not make the pack."
                        message == null -> "Too many add-ons for one chat message: use Share pack instead."
                        else -> { packs.send(message); "Offered in the room's chat." }
                    }
                }
            })
        }
        note?.let { Text(it, style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim, modifier = Modifier.padding(top = 10.dp)) }
        offers.filter { !it.mine }.take(5).forEach { offer ->
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${offer.from} shares their add-ons", style = fonts.body, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                WatchCardChip("Import", look.ink, fonts, icon = CosmoIcons.Download, onClick = { onImport(offer.link) })
            }
        }
    }
}

/** The desktop's "Set as handler": makes Cosmotune the app that opens stremio:// and cosmotune://watch links, on a press only. */
@Composable
private fun LinkHandlerCard(handler: WatchLinkHandler, fonts: OrbitFonts, look: OrbitLook) {
    val scope = rememberCoroutineScope()
    var set by remember { mutableStateOf<Boolean?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(handler) { set = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { handler.isSet() }.getOrDefault(false) } }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(look.row).padding(18.dp)) {
        Text("Install links", style = fonts.title, color = look.ink)
        Text("Open stremio:// and cosmotune:// links from a web page with Cosmotune, so an add-on installs in one tap.", style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim,
            modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WatchCardChip(if (set == true) "Cosmotune opens them" else "Set as handler", look.ink, fonts, icon = if (set == true) CosmoIcons.Check else CosmoIcons.Share,
                enabled = set == false, onClick = {
                    scope.launch {
                        val problem = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { handler.set() }.getOrElse { it.message ?: "Could not set it." } }
                        note = problem
                        set = problem == null || runCatching { handler.isSet() }.getOrDefault(false)
                    }
                })
        }
        note?.let { Text(it, style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim, modifier = Modifier.padding(top = 10.dp)) }
    }
}

/** TMDB's attribution, as its terms ask of every app that uses its API: the mark and the notice. */
@Composable
internal fun TmdbNotice(fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TmdbMark()
        Text("This product uses the TMDB API but is not endorsed or certified by TMDB.", style = fonts.body.copy(fontSize = 12.sp, lineHeight = 16.sp), color = look.dim,
            modifier = Modifier.weight(1f))
    }
}

/** TMDB's short mark: the letters in its dark blue on its green-to-blue pill. */
@Composable
internal fun TmdbMark(modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF90CEA1), Color(0xFF3CBEC9), Color(0xFF01B4E4))))
            .padding(horizontal = 10.dp, vertical = 3.dp).semantics { contentDescription = "TMDB" },
        contentAlignment = Alignment.Center,
    ) {
        Text("TMDB", style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black, letterSpacing = 1.sp),
            color = Color(0xFF0D253F))
    }
}

@Composable
private fun PluginRow(p: PluginInfo, look: OrbitLook, fonts: OrbitFonts, onToggle: (Boolean) -> Unit, menu: List<OrbitMenuItem> = emptyList()) {
    var open by remember { mutableStateOf(false) }
    val line = if (p.error) p.status ?: p.description else listOfNotNull(p.description, p.status?.takeIf { it !in p.description }).joinToString(" · ")
    WatchPluginCard(p.name, line, p.badge, fonts, look, error = p.error) {
        if (menu.isNotEmpty()) Box {
            WatchRoundButton(CosmoIcons.More, "More for ${p.name}", look, onClick = { open = true }, size = 36.dp)
            OrbitMenu(open, onDismiss = { open = false }, items = menu, fonts = fonts, look = look, header = p.name)
        }
        OrbitSwitch(p.enabled, onToggle, look, label = p.name)
    }
}

/** "Add a plugin or source" (board section 5): the indigo card, the plus in its bite. */
@Composable
private fun AddCard(fonts: OrbitFonts, look: OrbitLook, modifier: Modifier, onClick: () -> Unit) {
    OrbitBiteCard(WATCH_ADD_FILL, modifier.fillMaxWidth(), onClick = onClick, bite = {
        Box(
            Modifier.size(66.dp).clip(CircleShape).background(if (look.dark) PEACH else Color(0xFF1D1416))
                .clickable(role = Role.Button, onClickLabel = "Add a plugin or source", onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(CosmoIcons.Plus, "Add a plugin or source", tint = if (look.dark) Color(0xFF2A100A) else PEACH, modifier = Modifier.size(26.dp)) }
    }) {
        Column(Modifier.padding(start = 22.dp, top = 22.dp, end = 100.dp, bottom = 26.dp)) {
            OrbitLabel("Add a plugin or source", fonts, WATCH_ADD_INK.copy(alpha = 0.75f))
            Text("Plugin link, server or playlist", style = fonts.section.copy(fontSize = 22.sp, lineHeight = 26.sp), color = WATCH_ADD_INK, modifier = Modifier.padding(top = 10.dp))
            Text("Stremio / Nuvio add-on URL · Jellyfin, Plex, Emby · M3U, Xtream · a folder", style = fonts.body.copy(fontSize = 13.sp), color = WATCH_ADD_INK.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun PackCard(fonts: OrbitFonts, look: OrbitLook, onShare: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(look.row).padding(18.dp)) {
        Text("Share your plugins", style = fonts.title, color = look.ink)
        Text("A link and a QR code with your add-on links only: never a server, a playlist with a password or a sign-in.", style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim,
            modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WatchCardChip("Share pack", look.ink, fonts, onShare, icon = CosmoIcons.Share)
            WatchCardChip("Import pack", look.ink, fonts, onImport, icon = CosmoIcons.Download)
        }
    }
}

/**
 * "Find plugins" (the head of the page): a flat teal card that says where more plugins, playlists and servers are found, with each
 * directory as a row that opens in the web browser (two to a line when the page is wide). Links only: nothing is fetched from them,
 * and a link found there is added with "Add a plugin or source".
 */
@Composable
private fun FindPlugins(repository: WatchRepository, fonts: OrbitFonts, look: OrbitLook, wide: Boolean, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    val ink = FIND_INK
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(FIND_FILL).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp)) {
        OrbitLabel("Find plugins · opens in your browser", fonts, ink.copy(alpha = 0.75f))
        Text("More to watch", style = fonts.section.copy(fontSize = 22.sp, lineHeight = 26.sp), color = ink, modifier = Modifier.padding(top = 10.dp))
        Text("Directories of free add-ons, channel playlists and servers. Copy a link from one and add it under \"Add a plugin or source\".",
            style = fonts.body.copy(fontSize = 13.sp), color = ink.copy(alpha = 0.82f), modifier = Modifier.padding(top = 6.dp))
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repository.pluginDirectories.chunked(if (wide) 2 else 1).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.forEach { d -> DirectoryRow(d, ink, fonts, Modifier.weight(1f)) { runCatching { uri.openUri(d.url) } } }
                    if (line.size == 1 && wide) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** The teal of "Find plugins" and the pale ink on it. */
private val FIND_FILL = Color(0xFF1C4C49)
private val FIND_INK = Color(0xFFE6F6F2)

/** One directory on the "Find plugins" card: its letter tile, name and line, and the open mark; a tap opens it in the browser. */
@Composable
private fun DirectoryRow(d: com.cosmotune.watch.DirectoryLink, ink: Color, fonts: OrbitFonts, modifier: Modifier, onOpen: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier.height(64.dp).cosmoFocusRing(interaction, 20.dp).clip(RoundedCornerShape(20.dp)).background(ink.copy(alpha = 0.10f))
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClickLabel = "Open ${d.title} in the browser", onClick = onOpen)
            .padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WatchLogoTile(d.title.take(1).uppercase(), logoFill(d.title), fonts, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(d.title, style = fonts.title.copy(fontSize = 14.sp), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(d.description, style = fonts.body.copy(fontSize = 12.sp, lineHeight = 15.sp), color = ink.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(CosmoIcons.ChevronRight, contentDescription = null, tint = ink.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
    }
}

// ---- dialogs ------------------------------------------------------------------------------------------------------------------

/**
 * Watch's dialogs in Orbit's dialog card ([com.cosmotune.ui.sheets.CosmoDialog]): the mono [label] and the wide [title] as the card's
 * heading, then [content], which ends with [com.cosmotune.ui.sheets.CosmoDialogActions] (one peach action and a quiet Cancel).
 */
@Composable
internal fun WatchDialog(label: String, title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    com.cosmotune.ui.sheets.CosmoDialog(onDismiss = onDismiss, maxWidth = 540.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp)) {
            com.cosmotune.ui.sheets.CosmoDialogHeading(label, title, null)
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

/**
 * "Check for update" on a Watch script: downloads its source, says whether it changed, and installs
 * the downloaded version only when asked. Nothing changes until Install, and Install takes exactly
 * the copy that was checked.
 */
@Composable
private fun ScriptUpdateDialog(plugin: PluginInfo, repository: WatchRepository, fonts: OrbitFonts, look: OrbitLook, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var update by remember(plugin.id) { mutableStateOf<com.cosmotune.watch.WatchScriptUpdate?>(null) }
    var message by remember(plugin.id) { mutableStateOf<String?>(null) }
    var busy by remember(plugin.id) { mutableStateOf(true) }
    LaunchedEffect(plugin.id) {
        update = runCatching { repository.checkScriptUpdate(plugin.id) }.getOrElse { message = it.message ?: "Could not read the script's source."; null }
        if (update == null && message == null) message = "This plugin has no script to update."
        busy = false
    }
    WatchDialog("Watch script", plugin.name, onDone) {
        val found = update
        val line = when {
            busy -> "Reading the script's source…"
            found == null -> message
            !found.available -> "Up to date. The source matches the installed copy."
            else -> "A changed version is available (${(found.sizeBytes + 1023) / 1024} KB). Install only if you trust its author; it runs with the same permissions as now."
        }
        line?.let { Text(it, style = fonts.body, color = look.dim, modifier = Modifier.padding(bottom = 12.dp)) }
        if (found != null) {
            OrbitLabel("Source · ${found.url.substringAfter("://").substringBefore('/')}", fonts, look.dim, Modifier.padding(start = 6.dp, bottom = 4.dp))
            OrbitLabel("Installed · ${found.installedSha256.take(12)}", fonts, look.dim, Modifier.padding(start = 6.dp, bottom = 4.dp))
            if (found.available) OrbitLabel("Available · ${found.availableSha256.take(12)}", fonts, look.dim, Modifier.padding(start = 6.dp, bottom = 12.dp))
        }
        com.cosmotune.ui.sheets.CosmoDialogActions(
            confirmLabel = if (busy && found != null) "Installing…" else "Install update",
            onConfirm = {
                val offer = found ?: return@CosmoDialogActions
                busy = true
                scope.launch {
                    val done = runCatching { repository.applyScriptUpdate(offer.id, offer.availableSha256) }.getOrDefault(false)
                    busy = false
                    if (done) onDone() else message = "The update could not be installed. Check again."
                    if (!done) update = null
                }
            },
            confirmEnabled = !busy && found?.available == true,
            dismissLabel = "Close",
            onDismiss = onDone,
        )
    }
}

/** A labelled text field in the chip's fill. */
@Composable
internal fun WatchField(label: String, value: String, onChange: (String) -> Unit, fonts: OrbitFonts, look: OrbitLook, placeholder: String = "", password: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        OrbitLabel(label, fonts, look.dim, Modifier.padding(start = 6.dp, bottom = 6.dp))
        Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(look.chip).padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
            val style = fonts.body.copy(fontSize = 15.sp, color = look.ink)
            if (value.isEmpty()) Text(placeholder, style = style, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(value, onChange, singleLine = true, textStyle = style, cursorBrush = SolidColor(look.accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None, modifier = Modifier.fillMaxWidth())
        }
    }
}

private enum class SourceKind(val label: String) { ADDON("Add-on link"), SCRIPT("Watch JavaScript"), REPOSITORY("Nuvio repository"), JELLYFIN("Jellyfin"), EMBY("Emby"), PLEX("Plex"), M3U("M3U playlist"), XTREAM("Xtream"), FOLDER("Folder") }

/** "Add a plugin or source": the kind as chips, its fields, and for an add-on link the preview of its manifest before installing. */
@Composable
private fun AddSourceDialog(repository: WatchRepository, fonts: OrbitFonts, look: OrbitLook, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(SourceKind.ADDON) }
    var scripts by remember { mutableStateOf<List<com.cosmotune.watch.WatchScriptEntry>?>(null) }
    var selectedScript by remember { mutableStateOf<com.cosmotune.watch.WatchScriptEntry?>(null) }
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var c by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<InstallPreview?>(null) }
    WatchDialog("Add a plugin or source", "Plugin link, server or playlist", onDone) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceKind.entries.forEach { k -> OrbitSelectChip(k.label, k == kind, fonts, look, onClick = { kind = k; a = ""; b = ""; c = ""; preview = null; scripts = null; selectedScript = null; message = null }, haptic = false) }
        }
        when (kind) {
            SourceKind.ADDON -> WatchField("Add-on link", a, { a = it; preview = null }, fonts, look, "https://…/manifest.json or stremio://…")
            SourceKind.REPOSITORY -> {
                WatchField("Nuvio manifest URL", a, { a = it; scripts = null; selectedScript = null }, fonts, look, "https://…/manifest.json")
                scripts?.let { entries ->
                    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        entries.forEach { entry -> OrbitSelectChip(entry.name, entry.url == selectedScript?.url, fonts, look, onClick = { selectedScript = entry }, haptic = false) }
                        if (entries.isEmpty()) Text("No available scripts in this repository", style = fonts.body, color = look.dim)
                    }
                }
            }
            SourceKind.SCRIPT -> {
                WatchField("JavaScript source URL", a, { a = it }, fonts, look, "https://…/provider.js")
            }
            SourceKind.JELLYFIN, SourceKind.EMBY, SourceKind.XTREAM -> {
                WatchField("Server", a, { a = it }, fonts, look, "https://media.example.org")
                WatchField("User", b, { b = it }, fonts, look)
                WatchField("Password", c, { c = it }, fonts, look, password = true)
            }
            SourceKind.PLEX -> { WatchField("Server", a, { a = it }, fonts, look, "https://plex.example.org:32400"); WatchField("Token", b, { b = it }, fonts, look, password = true) }
            SourceKind.M3U -> { WatchField("Playlist link", a, { a = it }, fonts, look, "https://…/playlist.m3u"); WatchField("Guide link (XMLTV, optional)", b, { b = it }, fonts, look) }
            SourceKind.FOLDER -> WatchField("Folder", a, { a = it }, fonts, look, "A folder of films and series on this device")
        }
        if (kind == SourceKind.SCRIPT || kind == SourceKind.REPOSITORY) {
            WatchField("Restrict domains (optional)", b, { b = it }, fonts, look, "example.com, *.example.com")
            WatchField("Script settings JSON (optional)", c, { c = it.take(16_384) }, fonts, look, "{}")
            Text("Install scripts you trust. Blank domains allow public HTTPS access; local-network requests, device files and native code are blocked. Settings stay on this device. Browser-only APIs need a compatible script.", style = fonts.body, color = look.dim)
        }
        if (kind != SourceKind.ADDON) WatchField("Name (optional)", name, { name = it }, fonts, look)
        preview?.let { InstallPreviewCard(it, fonts, look, Modifier.padding(bottom = 14.dp)) }
        message?.let { Text(it, style = fonts.body, color = look.dim, modifier = Modifier.padding(bottom = 12.dp)) }
        val previewing = kind == SourceKind.ADDON && preview == null || kind == SourceKind.REPOSITORY && scripts == null
        val ready = when (kind) {
            SourceKind.SCRIPT -> a.isNotBlank()
            SourceKind.REPOSITORY -> selectedScript != null
            SourceKind.ADDON -> preview?.installable == true
            SourceKind.JELLYFIN, SourceKind.EMBY, SourceKind.XTREAM -> a.isNotBlank() && b.isNotBlank()
            SourceKind.PLEX -> a.isNotBlank() && b.isNotBlank()
            else -> a.isNotBlank()
        }
        com.cosmotune.ui.sheets.CosmoDialogActions(
            confirmLabel = when {
                previewing -> if (busy) "Reading…" else "Preview"
                busy -> "Checking…"
                kind == SourceKind.ADDON -> "Install"
                else -> "Add"
            },
            onConfirm = {
                busy = true; message = null
                if (previewing) {
                    scope.launch {
                        if (kind == SourceKind.REPOSITORY) scripts = runCatching { repository.scriptRepository(a.trim()) }.getOrElse { message = "Could not read that repository"; null }
                        else preview = runCatching { repository.previewInstallLink(a) }.getOrElse { e -> message = e.message ?: "Could not read that link."; null }
                        busy = false
                    }
                } else {
                    scope.launch {
                        val result = runCatching {
                            when (kind) {
                                SourceKind.ADDON -> repository.installFromLink(a)
                                SourceKind.SCRIPT, SourceKind.REPOSITORY -> {
                                    val settings = kotlinx.serialization.json.Json.parseToJsonElement(c.ifBlank { "{}" }) as? kotlinx.serialization.json.JsonObject ?: error("Use a JSON settings object")
                                    val url = if (kind == SourceKind.REPOSITORY) requireNotNull(selectedScript).url else a.trim()
                                    repository.addUserSource(UserSourceSpec.NuvioScript(url, b.split(',').map { it.trim() }.filter { it.isNotBlank() },
                                        name.trim().ifBlank { selectedScript?.name.orEmpty() }, settings = settings))
                                }
                                SourceKind.JELLYFIN -> repository.addUserSource(UserSourceSpec.Jellyfin(a.trim(), b.trim(), c, name.trim()))
                                SourceKind.EMBY -> repository.addUserSource(UserSourceSpec.Emby(a.trim(), b.trim(), c, name.trim()))
                                SourceKind.PLEX -> repository.addUserSource(UserSourceSpec.Plex(a.trim(), b.trim(), name.trim()))
                                SourceKind.M3U -> repository.addUserSource(UserSourceSpec.M3u(a.trim(), b.trim().ifBlank { null }, name.trim()))
                                SourceKind.XTREAM -> repository.addUserSource(UserSourceSpec.Xtream(a.trim(), b.trim(), c, name.trim()))
                                SourceKind.FOLDER -> repository.addUserSource(UserSourceSpec.LocalFolder(a.trim(), name.trim()))
                            }
                        }.getOrElse { AddSourceResult.Failed(it.message ?: "Could not add it.") }
                        busy = false
                        when (result) {
                            is AddSourceResult.Added -> onDone()
                            is AddSourceResult.Failed -> message = result.message
                        }
                    }
                }
            },
            confirmEnabled = !busy && if (previewing) a.isNotBlank() else ready,
            dismissLabel = "Cancel",
            onDismiss = onDone,
        )
    }
}

/** What an add-on link offers, read from its manifest: its mark, name, version, description, what it brings, and any problem. */
@Composable
internal fun InstallPreviewCard(p: InstallPreview, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(look.row).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WatchLogoTile("ADD", logoFill("ADD"), fonts, logoUrl = p.logoUrl)
            Column(Modifier.weight(1f)) {
                Text(p.name, style = fonts.title, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                OrbitLabel(listOfNotNull(p.version?.let { "v$it" }, p.manifestUrl.substringAfter("://").substringBefore('/')).joinToString(" · "), fonts, look.dim)
            }
        }
        p.description?.let { Text(it, style = fonts.body, color = look.dim, modifier = Modifier.padding(top = 10.dp)) }
        if (p.offers.isNotEmpty()) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { p.offers.forEach { WatchBadge(it, fonts, look.ink) } }
        val note = p.problem ?: if (p.alreadyInstalled) "Already installed." else null
        note?.let { Text(it, style = fonts.body, color = if (p.problem != null) (if (look.dark) WATCH_LIVE else WATCH_LIVE_INK) else look.dim, modifier = Modifier.padding(top = 10.dp)) }
        Text("It speaks a read-only protocol: no code is downloaded or run.", style = fonts.body.copy(fontSize = 12.sp), color = look.dim, modifier = Modifier.padding(top = 10.dp))
    }
}

/** The sheet a one-tap install link opens: the add-on's preview, then Install or Cancel. */
@Composable
internal fun WatchInstallSheet(link: String, fonts: OrbitFonts, look: OrbitLook, onDone: () -> Unit) {
    val repository = watchRepository()
    val scope = rememberCoroutineScope()
    var preview by remember(link) { mutableStateOf<InstallPreview?>(null) }
    var message by remember(link) { mutableStateOf<String?>(null) }
    var busy by remember(link) { mutableStateOf(false) }
    LaunchedEffect(link) { preview = runCatching { repository.previewInstallLink(link) }.getOrElse { message = it.message ?: "Could not read that link."; null } }
    WatchDialog("Install link", "Install this add-on?", onDone) {
        val p = preview
        if (p == null && message == null) Text("Reading the add-on…", style = fonts.body, color = look.dim)
        p?.let { InstallPreviewCard(it, fonts, look) }
        message?.let { Text(it, style = fonts.body, color = look.dim, modifier = Modifier.padding(top = 10.dp)) }
        com.cosmotune.ui.sheets.CosmoDialogActions(
            confirmLabel = if (busy) "Installing…" else "Install",
            onConfirm = {
                busy = true
                scope.launch {
                    when (val r = runCatching { repository.installFromLink(link) }.getOrElse { AddSourceResult.Failed(it.message ?: "Could not install it.") }) {
                        is AddSourceResult.Added -> onDone()
                        is AddSourceResult.Failed -> { message = r.message; busy = false }
                    }
                }
            },
            confirmEnabled = p?.installable == true && !busy,
            dismissLabel = "Cancel",
            onDismiss = onDone,
        )
    }
}

/** Share a pack: the link (and its QR code where the host draws one) to copy or scan on another device. */
@Composable
private fun SharePackDialog(repository: WatchRepository, fonts: OrbitFonts, look: OrbitLook, onDone: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val qr = LocalWatchQrCode.current
    var link by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { link = runCatching { repository.exportPluginPack() }.getOrNull() }
    WatchDialog("Share your plugins", "Your plugin pack", onDone) {
        val l = link
        if (l == null) Text("Making the link…", style = fonts.body, color = look.dim)
        else {
            if (qr != null) Box(Modifier.fillMaxWidth().padding(bottom = 14.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.clip(RoundedCornerShape(22.dp)).background(Color.White).padding(14.dp)) { qr(l, 200.dp) }
            }
            Text(l, style = fonts.label.copy(fontSize = 11.sp), color = look.ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(look.chip).padding(14.dp))
            Text("Add-on links only. Servers, playlists with passwords and sign-ins stay on this device.", style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim,
                modifier = Modifier.padding(top = 10.dp))
            com.cosmotune.ui.sheets.CosmoDialogActions(
                confirmLabel = if (copied) "Copied" else "Copy link",
                onConfirm = { clipboard.setText(AnnotatedString(l)); copied = true },
                dismissLabel = "Done",
                onDismiss = onDone,
            )
        }
    }
}

/**
 * Import a pack: paste its link (or arrive with one: a pack link opened from outside, or offered in a room), see what it holds
 * with a switch each, then add the chosen ones.
 */
@Composable
internal fun ImportPackDialog(repository: WatchRepository, fonts: OrbitFonts, look: OrbitLook, initialLink: String? = null, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf(initialLink.orEmpty()) }
    var items by remember { mutableStateOf<List<PluginPackItem>?>(null) }
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(initialLink) {
        val start = initialLink?.trim()?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        items = runCatching { repository.readPluginPack(start) }.getOrElse { message = it.message ?: "That is not a plugin pack."; null }
        chosen = items.orEmpty().filter { !it.alreadyInstalled }.map { it.manifestUrl }.toSet()
    }
    WatchDialog("Import plugins", "Add a plugin pack", onDone) {
        WatchField("Pack link", link, { link = it; items = null }, fonts, look, "cosmotune://watch/pack?d=…")
        items?.let { list ->
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.forEach { item ->
                    WatchPluginCard(item.name, if (item.alreadyInstalled) "Already installed" else item.manifestUrl.substringAfter("://").substringBefore('/'), "ADD", fonts, look) {
                        OrbitSwitch(item.manifestUrl in chosen, { on -> chosen = if (on) chosen + item.manifestUrl else chosen - item.manifestUrl }, look, item.name, enabled = !item.alreadyInstalled)
                    }
                }
            }
        }
        message?.let { Text(it, style = fonts.body, color = look.dim, modifier = Modifier.padding(bottom = 12.dp)) }
        val reading = items == null
        com.cosmotune.ui.sheets.CosmoDialogActions(
            confirmLabel = if (reading) (if (busy) "Reading…" else "Read pack") else (if (busy) "Adding…" else "Add ${chosen.size}"),
            onConfirm = {
                busy = true
                if (reading) {
                    message = null
                    scope.launch {
                        items = runCatching { repository.readPluginPack(link.trim()) }.getOrElse { message = it.message ?: "That is not a plugin pack."; null }
                        chosen = items.orEmpty().filter { !it.alreadyInstalled }.map { it.manifestUrl }.toSet()
                        busy = false
                    }
                } else {
                    scope.launch {
                        val r = runCatching { repository.importPluginPack(link.trim(), chosen) }.getOrNull()
                        busy = false
                        if (r == null) message = "Could not import the pack."
                        else if (r.failed.isEmpty()) onDone()
                        else message = "Added ${r.added.size}. Not added: " + r.failed.entries.joinToString { "${it.key.substringAfter("://").substringBefore('/')} (${it.value})" }
                    }
                }
            },
            confirmEnabled = !busy && if (reading) link.isNotBlank() else chosen.isNotEmpty(),
            dismissLabel = "Cancel",
            onDismiss = onDone,
        )
    }
}

/** Shared Watch preferences, separate from installing and enabling content sources. */
@Composable
internal fun WatchSettingsPage(fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame) {
    val repository = watchRepository()
    val scope = rememberCoroutineScope()
    val side = frame.side
    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(), contentPadding = frame.padding) {
        frame.header?.let { header -> item(key = "settings:header") { header() } }
        item(key = "settings:heading") {
            OrbitPageHeading("Watch settings", "Display, viewers and connected services", fonts, look,
                Modifier.padding(horizontal = side).padding(top = 24.dp))
        }
            item(key = "settings:display") {
                val preferences by repository.preferences.collectAsState()
                Column(Modifier.padding(horizontal = side).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OrbitLabel("Poster size", fonts, look.dim)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.cosmotune.watch.WatchDensity.entries.forEach { density ->
                            OrbitSelectChip(density.label, preferences.density == density, fonts, look, onClick = {
                                scope.launch { repository.setPreferences(preferences.copy(density = density)) }
                            }, haptic = false)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OrbitSwitch(preferences.animeSkips, { enabled -> scope.launch { repository.setPreferences(preferences.copy(animeSkips = enabled)) } }, look, "Offer anime opening and ending skips")
                        Text("Offer anime opening and ending skips", style = fonts.body, color = look.ink)
                    }
                }
            }
            item(key = "settings:profiles") {
                val profiles by repository.profiles.collectAsState()
                val active by repository.activeProfile.collectAsState()
                var name by remember { mutableStateOf("") }
                var lockedTarget by remember { mutableStateOf<com.cosmotune.watch.model.WatchProfile?>(null) }
                var pin by remember { mutableStateOf("") }
                var editingPin by remember { mutableStateOf(false) }
                var removing by remember { mutableStateOf(false) }
                var profileMessage by remember { mutableStateOf<String?>(null) }
                Column(Modifier.padding(horizontal = side).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OrbitLabel("Watch profiles", fonts, look.dim)
                    Text("Separate My list and Continue watching for each viewer.", style = fonts.body, color = look.dim)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        profiles.forEach { profile ->
                            OrbitSelectChip(profile.name, active == profile.id, fonts, look, onClick = {
                                if (profile.locked && profile.id != active) { pin = ""; lockedTarget = profile; profileMessage = null }
                                else scope.launch { repository.selectProfile(profile.id) }
                            }, haptic = false)
                        }
                    }
                    WatchField("New profile name", name, { name = it.take(40) }, fonts, look)
                    WatchCardChip("Add profile", look.ink, fonts, enabled = name.isNotBlank() && profiles.size < 8, onClick = {
                        scope.launch { repository.addProfile(name)?.let { repository.selectProfile(it); name = "" } }
                    }, icon = CosmoIcons.Plus)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WatchCardChip("Profile PIN", look.ink, fonts, onClick = { pin = ""; editingPin = true; profileMessage = null })
                        if (active != "default") WatchCardChip("Delete profile", look.ink, fonts, onClick = { removing = true; profileMessage = null })
                    }
                    profileMessage?.let { Text(it, style = fonts.body, color = look.dim) }
                }
                lockedTarget?.let { target ->
                    com.cosmotune.ui.sheets.CosmoAlertDialog(onDismissRequest = { lockedTarget = null }, title = "Unlock ${target.name}",
                        confirmLabel = "Unlock", dismissLabel = "Cancel", onConfirm = {
                            scope.launch { if (repository.selectProfile(target.id, pin)) { lockedTarget = null; profileMessage = null } else profileMessage = "The PIN did not match" }
                        }) {
                        WatchField("PIN", pin, { pin = it.filter(Char::isDigit).take(8) }, fonts, look, password = true)
                        profileMessage?.let { Text(it, style = fonts.body, color = look.dim) }
                    }
                }
                if (editingPin) com.cosmotune.ui.sheets.CosmoAlertDialog(onDismissRequest = { editingPin = false }, title = "Profile PIN",
                    message = "Use four to eight digits. Leave blank to remove the PIN. This lock stays on this device.",
                    confirmLabel = "Save", dismissLabel = "Cancel", confirmEnabled = pin.isEmpty() || pin.length in 4..8,
                    onConfirm = { scope.launch { repository.setProfilePin(pin.takeIf { it.isNotBlank() }); editingPin = false } }) {
                    WatchField("New PIN", pin, { pin = it.filter(Char::isDigit).take(8) }, fonts, look, password = true)
                }
                if (removing) com.cosmotune.ui.sheets.CosmoAlertDialog(onDismissRequest = { removing = false }, title = "Delete this Watch profile?",
                    message = "Its saved titles and viewing progress will be removed. Everyone is kept.", confirmLabel = "Delete", dismissLabel = "Cancel",
                    onConfirm = { scope.launch { runCatching { repository.removeProfile(active) }.onFailure { profileMessage = it.message }; removing = false } })
            }
            repository.tracking?.let { tracking -> item(key = "settings:tracking") {
                Box(Modifier.padding(horizontal = side).padding(top = 24.dp)) { WatchTrackingCard(tracking, fonts, look) }
            } }
            item(key = "settings:services") {
                val configured by repository.configuredServices.collectAsState()
                var service by remember { mutableStateOf("omdb") }
                var apiKey by remember(service) { mutableStateOf("") }
                var message by remember { mutableStateOf<String?>(null) }
                Column(Modifier.padding(horizontal = side).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OrbitLabel("Additional ratings and subtitles", fonts, look.dim)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("omdb" to "OMDb ratings", "subdl" to "SubDL subtitles").forEach { (id, label) ->
                            OrbitSelectChip(label + if (id in configured) " · connected" else "", service == id, fonts, look, onClick = { service = id; message = null }, haptic = false)
                        }
                    }
                    WatchField("API key", apiKey, { apiKey = it.take(200) }, fonts, look, password = true)
                    Text("Keys stay in protected device storage. OMDb supplies attributed IMDb, Rotten Tomatoes and Metacritic ratings when available. SubDL adds individual subtitle files to the player’s subtitle search.", style = fonts.body, color = look.dim)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WatchCardChip("Save key", look.ink, fonts, enabled = apiKey.isNotBlank(), onClick = {
                            scope.launch { runCatching { repository.setServiceKey(service, apiKey) }.onSuccess { apiKey = ""; message = "Saved" }.onFailure { message = "The key could not be saved" } }
                        })
                        if (service in configured) WatchCardChip("Remove key", look.ink, fonts, onClick = { scope.launch { repository.setServiceKey(service, "") } })
                    }
                    message?.let { Text(it, style = fonts.body, color = look.dim) }
                }
            }
        item(key = "settings:debrid") {
            Box(Modifier.padding(horizontal = side).padding(top = 24.dp, bottom = 24.dp)) { WatchDebridCard(repository, fonts, look) }
        }
        item(key = "settings:end") { Spacer(Modifier.height(40.dp)) }
    }
}
