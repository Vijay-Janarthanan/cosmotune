package com.cosmotune.tv

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.listentogether.ListenTogether
import com.cosmotune.music.data.settings.TvSettings
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.SearchResult
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.playback.PlayerState
import com.cosmotune.music.playback.playSongs
import com.cosmotune.music.ui.MainViewModel
import com.cosmotune.music.ui.tv.auth.TvQrCodeView
import com.cosmotune.music.ui.tv.components.TvButton
import com.cosmotune.music.ui.tv.components.TvDialog
import com.cosmotune.music.ui.tv.components.TvListRow
import com.cosmotune.music.ui.tv.theme.TvGlass
import com.cosmotune.remote.*
import kotlinx.coroutines.*
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

private data class TvRemoteInput(val controller: MediaController?, val player: PlayerState, val allowed: Boolean)

@Composable
internal fun rememberTvPhoneRemote(
    viewModel: MainViewModel, mediaController: MediaController?, player: PlayerState,
    accountId: String?, profileId: String?, allowed: Boolean, profileAccess: TvProfileAccess,
): LanRemoteController {
    val input = rememberUpdatedState(TvRemoteInput(mediaController, player, allowed))
    val status = remember(accountId, profileId) { AtomicReference(RemotePlayback()) }
    val tracks = remember(accountId, profileId) { LinkedHashMap<String, Song>() }
    val controller = remember(viewModel, accountId, profileId) {
        LanRemoteController(object : LanRemoteHost {
            private fun currentScope(): Boolean {
                val actual = viewModel.activeProfileId.value ?: profileId
                return input.value.allowed && viewModel.activeAccountId.value == accountId &&
                    (viewModel.activeProfileId.value == null || viewModel.activeProfileId.value == profileId) &&
                    profileAccess.allows(actual, actual != null && TvSettings.isProfileLocked(actual))
            }

            override fun playback() = if (currentScope()) status.get() else RemotePlayback()

            override suspend fun search(query: String): List<RemoteTrack> {
                check(currentScope()) { "Unlock the current TV profile first" }
                val local = withContext(Dispatchers.Main) {
                    val library = (viewModel.library.value as? UiState.Success)?.data
                    (input.value.player.queue + library?.likedSongs.orEmpty() + library?.librarySongs.orEmpty())
                        .asSequence().distinctBy { it.videoId }
                        .filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) }.take(50).toList()
                }
                val remote = if (query.trim().length >= 2) YtMusicRepository.search(query, SearchFilter.SONGS).let { result ->
                    if (result.isFailure && local.isEmpty()) throw result.exceptionOrNull() ?: IllegalStateException("Search unavailable")
                    result.getOrDefault(emptyList()).mapNotNull { row -> when (row) {
                        is SearchResult.Track -> row.song
                        is SearchResult.TopTrack -> row.song
                        else -> null
                    } }
                } else emptyList()
                currentCoroutineContext().ensureActive()
                check(currentScope()) { "The TV profile changed" }
                val found = (local + remote).distinctBy { it.videoId }.take(50)
                synchronized(tracks) {
                    found.forEach { tracks[it.videoId] = it }
                    while (tracks.size > 500) tracks.remove(tracks.keys.first())
                }
                return found.map { RemoteTrack(it.videoId, it.title, it.artist) }
            }

            override suspend fun command(command: RemoteCommand) = withContext(Dispatchers.Main) {
                check(currentScope()) { "Unlock the current TV profile first" }
                val current = checkNotNull(input.value.controller) { "The TV player is starting" }
                if (ListenTogether.state.value.controlsLocked && command.action != RemoteAction.VOLUME) error("Listen Together controls are locked")
                when (command.action) {
                    RemoteAction.PLAY -> { check(current.mediaItemCount > 0); current.play() }
                    RemoteAction.PAUSE -> current.pause()
                    RemoteAction.TOGGLE -> if (current.isPlaying) current.pause() else { check(current.mediaItemCount > 0); current.play() }
                    RemoteAction.NEXT -> if (current.hasNextMediaItem()) current.seekToNextMediaItem() else Unit
                    RemoteAction.PREVIOUS -> current.seekToPrevious()
                    RemoteAction.SEEK -> current.seekTo(checkNotNull(command.positionMs).let { if (current.duration > 0) it.coerceAtMost(current.duration) else it })
                    RemoteAction.VOLUME -> { current.volume = checkNotNull(command.volume) }
                    RemoteAction.PLAY_TRACK -> {
                        check(!ListenTogether.state.value.inParty) { "Leave Listen Together before replacing the queue" }
                        val song = synchronized(tracks) { tracks[command.trackId] } ?: error("Search for this recording again")
                        current.playSongs(listOf(song), 0)
                    }
                }
            }
        })
    }
    LaunchedEffect(controller, mediaController) {
        while (isActive) {
            val current = input.value
            status.set(if (!current.allowed) RemotePlayback() else RemotePlayback(
                current.player.song?.title ?: "Nothing playing", current.player.song?.artist.orEmpty(), current.player.isPlaying,
                current.player.position.positionMs, current.player.durationMs.takeIf { it > 0 }, current.controller?.volume))
            delay(250)
        }
    }
    LaunchedEffect(allowed, controller) { if (!allowed) controller.stop() }
    DisposableEffect(controller) { onDispose { controller.close(); synchronized(tracks) { tracks.clear() } } }
    return controller
}

@Composable
internal fun TvPhoneRemoteDialog(controller: LanRemoteController, onDismiss: () -> Unit) {
    val session by controller.session.collectAsState()
    val scope = rememberCoroutineScope()
    var addresses by remember { mutableStateOf<List<InetAddress>>(emptyList()) }
    var selected by remember { mutableStateOf<InetAddress?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(controller) {
        try { addresses = controller.addresses(); selected = addresses.firstOrNull() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "No local interface is available. Check the TV's network connection." }
    }
    fun work(block: suspend () -> Unit) { if (!busy) scope.launch {
        busy = true; error = null
        try { block() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "The remote could not start. Check the selected network and try again." }
        finally { busy = false }
    } }
    TvDialog(title = "Phone remote", width = 700.dp, onDismissRequest = onDismiss) {
        Text("Pair a browser on the same network for playback, volume, seeking and music search. Account cookies stay on the TV.", color = TvGlass.TextSecondary)
        error?.let { Text(it, color = TvGlass.TextPrimary) }
        val active = session
        if (active == null) {
            addresses.forEach { address -> TvListRow(
                title = if (address.isLoopbackAddress) "This TV only" else address.hostAddress.orEmpty(),
                value = if (selected == address) "Selected" else "",
                onClick = { if (!busy) selected = address },
            ) }
            TvButton("Start remote", onClick = { work { controller.start(checkNotNull(selected)) } }, enabled = selected != null && !busy)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                TvQrCodeView(active.pairingUrl, size = 176.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(active.origin, color = TvGlass.TextPrimary)
                    Text("Scan to pair. This private link controls Music until you stop the remote or change profiles.", color = TvGlass.TextSecondary)
                    TvButton("Stop remote", onClick = { work { controller.stop() } }, enabled = !busy)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        TvButton("Done", onClick = onDismiss, enabled = !busy)
    }
}
