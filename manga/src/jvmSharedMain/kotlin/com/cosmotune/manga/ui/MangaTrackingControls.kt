package com.cosmotune.manga.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.*
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.orbit.OrbitCardChip
import com.cosmotune.ui.orbit.OrbitCardColors
import com.cosmotune.ui.orbit.OrbitInputField
import com.cosmotune.ui.orbit.OrbitSwitchRow
import com.cosmotune.ui.orbit.orbitLook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The series card's Track chip: "Track", or the list it is on ("MangaUpdates · ch. 12"). Opens the tracking sheet. */
@Composable
internal fun MangaTrackChip(scope: MangaScope, book: MangaBook, colors: OrbitCardColors) {
    val state by scope.repository.store.state.collectAsState()
    val track = state.tracks[book.id].orEmpty().firstOrNull()
    var open by remember { mutableStateOf(false) }
    OrbitCardChip(track?.let { MangaTrackers.name(it.service) + if (it.chapter > 0) " · ch. ${it.chapter}" else "" } ?: mangaText("Track"), colors,
        onClick = { open = true }, icon = CosmoIcons.Check, modifier = Modifier.testTag("manga:track"))
    if (open) MangaTrackingSheet(scope, book) { open = false }
}

/**
 * Tracking for one series: sign in to MangaUpdates, find the series there and link it; once linked, the chapter count on the
 * Reading list, a way to the entry and Unlink. Every finished chapter then moves the count on.
 */
@Composable
internal fun MangaTrackingSheet(scope: MangaScope, book: MangaBook, onDismiss: () -> Unit) {
    val repository = scope.repository
    val state by repository.store.state.collectAsState()
    val look = orbitLook()
    val coroutines = rememberCoroutineScope()
    val account = state.trackerAccounts[MangaTrackers.MANGAUPDATES]
    val track = state.tracks[book.id].orEmpty().find { it.service == MangaTrackers.MANGAUPDATES }
    var query by remember(book.id) { mutableStateOf(book.title) }
    var results by remember(book.id) { mutableStateOf<List<MangaTrackerMatch>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun run(block: suspend () -> Unit) = coroutines.launch {
        busy = true; message = null
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { message = failure.message ?: "MangaUpdates could not be reached" }
        finally { busy = false }
    }
    KitSheet(onDismiss = onDismiss) {
        ReadSheetHeader(mangaText("Tracking"), onDone = onDismiss)
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("manga:tracking"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (book.sourceId.startsWith("komga:") || book.sourceId.startsWith("suwayomi:")) Text(mangaText(if (state.serverWriteBack) "Finished chapters are also marked read on your server."
                else "Marking chapters read on your server is off in Settings › Manga."), style = ReadType.meta(), color = look.dim)
            when {
                account == null -> MangaUpdatesSignIn(repository, Modifier.fillMaxWidth())
                track != null -> {
                    Text(track.title, style = ReadType.rowTitle(), color = look.ink)
                    Text(if (track.chapter > 0) "Chapter ${track.chapter} on your MangaUpdates list" else mangaText("On your MangaUpdates Reading list"), style = ReadType.meta(), color = look.dim)
                    if (scope.repository.store.state.value.trackFailures.contains(book.id)) Text(mangaText("The last chapter could not be sent. It is sent again with the next one."),
                        style = ReadType.meta(), color = look.dim)
                    Spacer(Modifier.height(4.dp))
                    val uri = LocalUriHandler.current
                    if (track.url.startsWith("https://")) ReadSheetRow(mangaText("Open on MangaUpdates"), Icons.AutoMirrored.Rounded.OpenInNew, { runCatching { uri.openUri(track.url) } })
                    ReadSheetRow(mangaText("Unlink"), CosmoIcons.Close, { run { repository.tracking.unlink(book.id, MangaTrackers.MANGAUPDATES) } }, enabled = !busy)
                }
                else -> {
                    Text(mangaText("Find this series on MangaUpdates. Finished chapters then update your Reading list."), style = ReadType.meta(), color = look.dim)
                    OrbitInputField(query, { query = it.take(200) }, mangaText("Title"), modifier = Modifier.fillMaxWidth().testTag("manga:tracking:query"))
                    ReadButton(mangaText(if (busy) "Searching…" else "Search"), { run { results = repository.tracking.search(query) } }, enabled = !busy && query.isNotBlank(),
                        kind = ReadKind.TONAL, modifier = Modifier.padding(top = 4.dp))
                    results?.let { found ->
                        if (found.isEmpty()) Text(mangaText("No series found. Try a shorter title."), style = ReadType.meta(), color = look.dim)
                        found.forEach { match ->
                            ReadSheetRow(match.title + if (match.detail.isNotBlank()) " · " + match.detail else "", CosmoIcons.Plus,
                                { run { repository.tracking.link(book, match) } }, enabled = !busy, modifier = Modifier.testTag("manga:tracking:match:${match.remoteId}"))
                        }
                    }
                }
            }
            message?.let { Text(it, style = ReadType.meta(), color = look.ink) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** User name, password, Sign in. The password goes to MangaUpdates once; only the session it returns is kept. */
@Composable
internal fun MangaUpdatesSignIn(repository: MangaRepository, modifier: Modifier = Modifier) {
    val look = orbitLook()
    val coroutines = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(mangaText("Sign in to MangaUpdates to keep your Reading list up to date as you read."), style = ReadType.meta(), color = look.dim)
        OrbitInputField(user, { user = it.take(256) }, mangaText("User name"), modifier = Modifier.fillMaxWidth().testTag("manga:mangaupdates:user"))
        OrbitInputField(password, { password = it.take(512) }, mangaText("Password"), secret = true, modifier = Modifier.fillMaxWidth())
        ReadButton(mangaText(if (busy) "Signing in…" else "Sign in"), enabled = !busy && user.isNotBlank() && password.isNotBlank(), kind = ReadKind.TONAL,
            modifier = Modifier.padding(top = 4.dp), onClick = { coroutines.launch {
                busy = true; message = null
                try { repository.tracking.signIn(user, password); password = "" }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { message = failure.message ?: "MangaUpdates could not be reached" }
                finally { busy = false }
            } })
        message?.let { Text(it, style = ReadType.meta(), color = look.ink) }
    }
}

/** Settings › Manga › Tracking: the MangaUpdates account and whether Komga hears about finished chapters. */
@Composable
internal fun MangaTrackingSettings(repository: MangaRepository) {
    val state by repository.store.state.collectAsState()
    val look = orbitLook()
    val coroutines = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReadOverline(mangaText("Tracking"), inset = 0.dp)
        val account = state.trackerAccounts[MangaTrackers.MANGAUPDATES]
        if (account == null) MangaUpdatesSignIn(repository, Modifier.fillMaxWidth())
        else {
            val linked = state.tracks.values.count { list -> list.any { it.service == MangaTrackers.MANGAUPDATES } }
            Text("MangaUpdates · ${account.user}", style = ReadType.rowTitle(), color = look.ink)
            Text(if (linked == 1) "1 series linked" else "$linked series linked", style = ReadType.meta(), color = look.dim)
            ReadTextButton(mangaText("Sign out"), { coroutines.launch { runCatching { repository.tracking.signOut(MangaTrackers.MANGAUPDATES) } } })
        }
        OrbitSwitchRow(mangaText("Mark chapters read on your server"), state.serverWriteBack, { on -> coroutines.launch { runCatching { repository.store.update { it.copy(serverWriteBack = on) } } } },
            plain = true, detail = mangaText("When you finish a chapter from Komga or Suwayomi"))
    }
}
