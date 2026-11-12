package com.cosmotune.music.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.spotify.SpotifyImportTrack
import com.cosmotune.music.data.spotify.SharedPlaylistLink
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitTextField
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoRadii
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The page's ink and its dim (Orbit), so this window's words read on a light page as on a dark one. */
private val inkColor: Color @Composable get() = com.cosmotune.ui.orbit.orbitLook().ink
private val dimColor: Color @Composable get() = com.cosmotune.ui.orbit.orbitLook().dim
private val groundColor: Color @Composable get() = com.cosmotune.ui.orbit.orbitLook().ground

/**
 * Signing in to Last.fm with a username and password.
 *
 * Android's `LastfmLoginAlert`: two fields, and the error takes the
 * description's place rather than appearing beside it, because a wrong password
 * is the only thing anybody needs told at that moment. The password is sent to
 * `auth.getMobileSession` and never stored — only the session key it returns.
 *
 * A build without Last.fm app credentials of its own (BC-35, upstream `6f30aa27`/`94cba2fa`) asks for the listener's own API
 * key and shared secret on the same screen; they are kept beside the session key in the desktop's settings (never in a
 * listening export, never logged) so later scrobbles can sign themselves.
 */
@Composable
internal fun DesktopLastfmLoginDialog(onDismiss: () -> Unit) {
    val needsAppKey = !DesktopScrobbling.hasBuiltInLastFmKey
    var apiKey by remember { mutableStateOf(DesktopScrobbleSettings.lastfmApiKey.value) }
    var apiSecret by remember { mutableStateOf(DesktopScrobbleSettings.lastfmApiSecret.value) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val ready = DesktopLastFmCredentials.signInReady(needsAppKey, apiKey, apiSecret, username, password)

    fun submit() {
        if (busy || !ready) return
        busy = true
        error = null
        // The sign-in request is signed with the app credentials, so they are saved first.
        if (needsAppKey) {
            DesktopScrobbleSettings.setLastfmApiKey(apiKey.trim())
            DesktopScrobbleSettings.setLastfmApiSecret(apiSecret.trim())
        }
        scope.launch {
            DesktopScrobbling.signInToLastFm(username.trim(), password)
                .onSuccess { onDismiss() }
                .onFailure { error = it.message ?: "Last.fm would not sign you in" }
            busy = false
        }
    }

    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 380) {
        DialogHeading(
            title = DesktopStrings["d_sign_in_to_last_fm", "Sign in to Last.fm"],
            message = error ?: if (needsAppKey) {
                "Your password is sent to Last.fm once and never stored. The API key and shared secret are your own app's, from last.fm/api."
            } else {
                "Your password is sent to Last.fm once and never stored."
            },
            isError = error != null,
        )
        if (needsAppKey) {
            DialogField(
                value = apiKey,
                onValueChange = { apiKey = it },
                placeholder = DesktopStrings["lastfm_api_key", "API key"],
                enabled = !busy,
                imeAction = ImeAction.Next,
            )
            Spacer(Modifier.height(8.dp))
            DialogField(
                value = apiSecret,
                onValueChange = { apiSecret = it },
                placeholder = DesktopStrings["lastfm_shared_secret", "Shared secret"],
                enabled = !busy,
                isPassword = true,
                imeAction = ImeAction.Next,
            )
            Spacer(Modifier.height(8.dp))
        }
        DialogField(
            value = username,
            onValueChange = { username = it },
            placeholder = DesktopStrings["username", "Username"],
            enabled = !busy,
            imeAction = ImeAction.Next,
            modifier = Modifier.focusRequester(focus),
        )
        Spacer(Modifier.height(8.dp))
        DialogField(
            value = password,
            onValueChange = { password = it },
            placeholder = DesktopStrings["password", "Password"],
            enabled = !busy,
            isPassword = true,
            onSubmit = ::submit,
        )
        DialogActions(
            confirm = if (busy) "Signing in…" else "Sign in",
            confirmEnabled = !busy && ready,
            busy = busy,
            onConfirm = ::submit,
            onDismiss = onDismiss,
        )
    }
}

/**
 * The `sp_dc` cookie Spotify Canvas needs.
 *
 * Android's `SpotifyCanvasAuthScreen`, with its four steps unchanged — they name a browser's
 * developer tools, which is where a desktop listener already is.
 */
@Composable
internal fun DesktopSpotifyCanvasDialog(onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    var token by remember { mutableStateOf(DesktopSpotifyToken.cookie()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun save() {
        DesktopSpotifyToken.setCookie(token.trim())
        onSaved(token.trim())
        onDismiss()
    }

    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 420) {
        DialogHeading(
            title = DesktopStrings["spotify_canvas_setup", "Spotify Canvas setup"],
            message = DesktopStrings["spotify_canvas_setup_description", "To use Spotify Canvas, provide your Spotify sp_dc cookie."],
        )
        Text(
            DesktopStrings[
                "spotify_canvas_setup_steps",
                "1. Sign in at open.spotify.com in a browser.\n" +
                    "2. Open Developer Tools, then Application and Cookies.\n" +
                    "3. Copy the value of the sp_dc cookie.\n" +
                    "4. Paste it below.",
            ],
            modifier = Modifier.fillMaxWidth().padding(horizontal = panelInset(dialogSide()), vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = dimColor,
        )
        Spacer(Modifier.height(12.dp))
        DialogField(
            value = token,
            onValueChange = { token = it },
            placeholder = DesktopStrings["spdc_token", "sp_dc token"],
            isPassword = true,
            onSubmit = ::save,
            modifier = Modifier.focusRequester(focus),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = panelInset(dialogSide())),
            horizontalArrangement = Arrangement.Start,
        ) {
            DialogLink(DesktopStrings["d_open_spotify", "Open Spotify"]) { DesktopExternalLinks.open("https://open.spotify.com/") }
        }
        DialogActions(
            confirm = DesktopStrings["save", "Save"],
            confirmEnabled = true,
            busy = false,
            onConfirm = ::save,
            onDismiss = onDismiss,
        )
    }
}

/**
 * ListenBrainz's user token.
 *
 * One field and no verification, as on Android: ListenBrainz has no endpoint
 * that says whether a token is good, so the first listen is the test.
 */
@Composable
internal fun DesktopListenBrainzTokenDialog(onDismiss: () -> Unit) {
    var token by remember { mutableStateOf(DesktopScrobbleSettings.listenBrainzToken.value) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun save() {
        DesktopScrobbleSettings.setListenBrainzToken(token.trim())
        DesktopScrobbleSettings.setListenBrainzEnabled(token.isNotBlank())
        onDismiss()
    }

    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 380) {
        DialogHeading(
            title = DesktopStrings["listenbrainz_token", "ListenBrainz token"],
            message = DesktopStrings["d_found_on_your_listenbrainz_profile_under_settings", "Found on your ListenBrainz profile, under Settings."],
        )
        DialogField(
            value = token,
            onValueChange = { token = it },
            placeholder = DesktopStrings["api_token", "API token"],
            isPassword = true,
            onSubmit = ::save,
            modifier = Modifier.focusRequester(focus),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = panelInset(dialogSide())),
            horizontalArrangement = Arrangement.Start,
        ) {
            DialogLink(DesktopStrings["d_open_listenbrainz", "Open ListenBrainz"]) { DesktopExternalLinks.open("https://listenbrainz.org/settings/") }
        }
        DialogActions(
            confirm = DesktopStrings["save", "Save"],
            confirmEnabled = true,
            busy = false,
            onConfirm = ::save,
            onDismiss = onDismiss,
        )
    }
}

/**
 * The side inset of everything in these dialogs: 24 dp, the heading's and the buttons' (`DesktopDialogButtons`), so a field, a note
 * or a link starts on the heading's edge.
 */
@Composable
private fun dialogSide(): Dp = 24.dp

@Composable
private fun DialogHeading(title: String, message: String, isError: Boolean = false) {
    com.cosmotune.ui.sheets.CosmoDialogHeading(
        overline = null,
        title = title,
        message = message,
        modifier = Modifier.padding(start = panelInset(dialogSide()), end = panelInset(dialogSide()), top = 24.dp, bottom = 10.dp),
        messageIsError = isError,
    )
}

/** Imports a public playlist from a pasted or opened link and reports entries the service or matcher could not supply. */
@Composable
internal fun DesktopSpotifyImportDialog(
    initialLink: String?,
    onImported: (title: String, songs: List<Song>) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf(initialLink.orEmpty()) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var unmatched by remember { mutableStateOf<List<SpotifyImportTrack>?>(null) }
    var unavailable by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    val title = DesktopStrings["spotify_import_title", "Import a playlist"]

    fun start() {
        if (working) return
        if (SharedPlaylistLink.fromText(link) == null) {
            status = DesktopStrings["spotify_import_invalid", "Choose a supported public playlist link."]
            failed = true
            return
        }
        working = true
        failed = false
        status = DesktopStrings["spotify_import_fetching", "Reading the playlist…"]
        scope.launch {
            try {
                val progress: (Int, Int) -> Unit = { done, total ->
                    status = DesktopStrings.format("spotify_import_matching", done, total, fallback = "Matching songs %1\$d of %2\$d")
                }
                val result = SharedPlaylistLink.import(link, progress)
                if (result.songs.isEmpty()) {
                    error(DesktopStrings["spotify_import_none", "None of those songs were found on YouTube Music."])
                }
                onImported(result.name, result.songs)
                unavailable = result.unavailable
                if (result.unmatched.isEmpty() && unavailable == 0) {
                    onDismiss()
                } else {
                    status = DesktopStrings["playlist_import_saved", "Playlist saved in Cosmotune."]
                    unmatched = result.unmatched
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                DesktopTrackLog.log("spotify import failed: ${failure.message}")
                status = DesktopStrings.format(
                    "spotify_import_failed",
                    failure.message.orEmpty(),
                    fallback = "Couldn't import the playlist: %1\$s",
                )
                failed = true
            } finally { working = false }
        }
    }

    LaunchedEffect(initialLink) {
        if (initialLink != null) start() else runCatching { focus.requestFocus() }
    }

    // Not dismissed mid-import, which would leave the matching running with nowhere to report.
    DesktopDialogPanel(onDismiss = { if (!working) onDismiss() }, maxWidth = 420, popup = true) {
        val missed = unmatched
        if (missed == null) {
            DialogHeading(
                title = title,
                message = DesktopStrings["spotify_import_description", "Paste a public Spotify, Amazon Music, Apple Music, Deezer or YouTube Music playlist link."],
            )
            DialogField(
                value = link,
                onValueChange = {
                    link = it
                    if (failed) status = null
                    failed = false
                },
                placeholder = DesktopStrings["spotify_import_hint", "open.spotify.com/playlist/…"],
                enabled = !working,
                onSubmit = ::start,
                modifier = Modifier.focusRequester(focus),
            )
            status?.let {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = panelInset(dialogSide()), vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) DesktopDestructive else dimColor,
                    textAlign = TextAlign.Center,
                )
            }
            DialogActions(
                confirm = DesktopStrings["spotify_import_button", "Import"],
                confirmEnabled = link.isNotBlank() && !working,
                busy = working,
                onConfirm = ::start,
                onDismiss = onDismiss,
            )
        } else {
            DialogHeading(
                title = title,
                message = listOfNotNull(
                    status,
                    DesktopStrings.format("playlist_import_unavailable", unavailable, fallback = "The source did not expose %1\$d tracks.")
                        .takeIf { unavailable > 0 },
                    DesktopStrings.format(
                        "spotify_import_unmatched",
                        missed.size,
                        fallback = "%1\$d songs weren't found on YouTube Music",
                    ).takeIf { missed.isNotEmpty() },
                ).joinToString("\n"),
            )
            if (missed.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = panelInset(dialogSide()))
                        .heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(CosmoRadii.rSm))
                        .background(inkColor.copy(alpha = 0.06f))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    missed.forEach { track ->
                        Text(
                            if (track.artist.isBlank()) track.title else "${track.title} · ${track.artist}",
                            style = MaterialTheme.typography.bodySmall,
                            color = dimColor,
                        )
                    }
                }
            }
            DesktopDialogButtons(null, {}, DesktopStrings["done", "Done"], onDismiss)
        }
    }
}

/** The same filled field the playlist dialogs use. */
@Composable
private fun DialogField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: () -> Unit = {},
) {
    // The Orbit field (the chip's pill) with the round clear button at its end once something is typed.
    val look = orbitLook()
    OrbitTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        fonts = orbitFonts(),
        look = look,
        modifier = Modifier.fillMaxWidth().padding(horizontal = panelInset(dialogSide())).then(modifier),
        secret = isPassword,
        enabled = enabled,
        onSubmit = onSubmit,
        imeAction = imeAction,
        trailing = if (value.isNotEmpty() && enabled) {
            { OrbitRoundButton(CosmoIcons.Close, DesktopStrings["clear", "Clear"], look.ink, { onValueChange("") }, size = 32.dp) }
        } else null,
    )
}

/** A way out to the service's own page under a field ("Open Spotify"): the quiet Orbit pill. */
@Composable
private fun DialogLink(label: String, onClick: () -> Unit) {
    OrbitPillButton(label, onClick, orbitFonts(), orbitLook(), Modifier.padding(top = 10.dp), height = 36.dp)
}

@Composable
private fun DialogActions(
    confirm: String,
    confirmEnabled: Boolean,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    DesktopDialogButtons(confirm, onConfirm, DesktopStrings["cancel", "Cancel"], onDismiss, confirmEnabled = confirmEnabled, busy = busy)
}

/**
 * Discord's user token.
 *
 * Verified before it is kept — Android's alert does the same, because a token
 * that was mistyped and one that has expired look identical until Discord is
 * asked about it.
 */
@Composable
internal fun DesktopDiscordTokenDialog(onDismiss: () -> Unit) {
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun save() {
        if (busy || token.isBlank()) return
        busy = true
        error = null
        scope.launch {
            DesktopDiscordRpc.signIn(token.trim())
                .onSuccess { onDismiss() }
                .onFailure { error = "Discord would not accept that token" }
            busy = false
        }
    }

    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 400) {
        DialogHeading(
            title = DesktopStrings["discord_token", "Discord token"],
            message = error
                ?: "Your account's own token, since Discord has no other way to let " +
                "an app set your presence.",
            isError = error != null,
        )
        DialogField(
            value = token,
            onValueChange = { token = it },
            placeholder = DesktopStrings["token", "Token"],
            enabled = !busy,
            isPassword = true,
            onSubmit = ::save,
            modifier = Modifier.focusRequester(focus),
        )
        DialogActions(
            confirm = if (busy) "Checking…" else "Save",
            confirmEnabled = !busy && token.isNotBlank(),
            busy = busy,
            onConfirm = ::save,
            onDismiss = onDismiss,
        )
    }
}


/**
 * One free-text value — an activity name, a button label.
 *
 * [message] is where the caller explains the field, including which `{...}`
 * variables it accepts, since that is the only place anyone would find out.
 */
@Composable
internal fun DesktopTextValueDialog(
    title: String,
    message: String,
    initial: String,
    placeholder: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    DesktopDialogPanel(onDismiss = onDismiss, maxWidth = 400, popup = true) {
        DialogHeading(title = title, message = message)
        DialogField(
            value = value,
            onValueChange = { value = it },
            placeholder = placeholder,
            onSubmit = { onSave(value.trim()) },
            modifier = Modifier.focusRequester(focus),
        )
        DialogActions(
            confirm = DesktopStrings["save", "Save"],
            confirmEnabled = true,
            busy = false,
            onConfirm = { onSave(value.trim()) },
            onDismiss = onDismiss,
        )
    }
}
