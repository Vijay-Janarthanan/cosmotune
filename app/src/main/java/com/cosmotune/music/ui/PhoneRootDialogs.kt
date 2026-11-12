package com.cosmotune.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.R
import com.cosmotune.music.auth.DiscordLoginScreen
import com.cosmotune.music.auth.GoogleAccountSession
import com.cosmotune.music.auth.SpotifyLoginScreen
import com.cosmotune.music.auth.WebSessionMode
import com.cosmotune.music.data.AppUpdateChecker
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.model.UserPlaylist
import com.cosmotune.music.data.room.PhoneRooms
import com.cosmotune.music.data.scrobbling.LastFM
import com.cosmotune.music.data.settings.AppSettings
import com.cosmotune.music.data.settings.SongSort
import com.cosmotune.music.data.sources.SourceConfig
import com.cosmotune.music.data.sources.SourceKind
import com.cosmotune.music.data.sources.SourceRegistry
import com.cosmotune.music.ui.components.AccountProfileSelector
import com.cosmotune.music.ui.components.AppLanguageDialog
import com.cosmotune.music.ui.components.ConfirmationAlert
import com.cosmotune.music.ui.components.FieldConfig
import com.cosmotune.music.ui.components.LastfmLoginAlert
import com.cosmotune.music.ui.components.ListenBrainzTokenAlert
import com.cosmotune.music.ui.components.LyricsSourcesDialog
import com.cosmotune.music.ui.components.ReorderPlaylistSheet
import com.cosmotune.music.ui.components.ServerEditorHost
import com.cosmotune.music.ui.components.TranslationLanguageDialog
import com.cosmotune.music.ui.components.WebDavConflictAlert
import com.cosmotune.music.ui.components.optimizedHazeEffect
import com.cosmotune.music.ui.room.PhoneRoomLauncher
import com.cosmotune.music.ui.screens.DiscordDialog
import com.cosmotune.music.ui.screens.DiscordDialogHost
import com.cosmotune.music.ui.screens.PartyServerEditor
import com.cosmotune.music.ui.screens.SourceEditorAlert
import com.cosmotune.music.ui.screens.SpotifyCanvasAuthScreen
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.room.RoomLauncher
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The dialogs, sheets and full-screen sign-ins that the phone's root draws over everything else, and the flags that open them.
 *
 * Out of the app's root composable (`CosmotuneApp` in `MainActivity.kt`), whose JVM method sits near the 64 KB limit: the blocks
 * here are the ones that need nothing of the root but a few values and two callbacks. The root still owns the flags (it opens
 * every one of these from its pages), through one [PhoneRootDialogState] it remembers, and calls [PhoneRootDialogs] where the
 * blocks used to be, so they are drawn in the same order, over the same pages, as before.
 */
@Stable
internal class PhoneRootDialogState {
    /**
     * What the in-app browser is open for, or null while it is closed: signing in, or picking a channel in YouTube Music's own
     * Accounts list.
     */
    val webSession: MutableState<WebSessionMode?> = mutableStateOf(null)
    val spotifyCanvasAuth: MutableState<Boolean> = mutableStateOf(false)
    val spotifyLogin: MutableState<Boolean> = mutableStateOf(false)
    val signatureApi: MutableState<Boolean> = mutableStateOf(false)
    val wearOs: MutableState<Boolean> = mutableStateOf(false)
    val editingSource: MutableState<SourceConfig?> = mutableStateOf(null)
    val confirmJioSaavn: MutableState<Boolean> = mutableStateOf(false)
    val editingPartyServer: MutableState<Boolean> = mutableStateOf(false)
    val lyricsSources: MutableState<Boolean> = mutableStateOf(false)
    val appLanguage: MutableState<Boolean> = mutableStateOf(false)
    val translationLanguage: MutableState<Boolean> = mutableStateOf(false)
    val accountSelector: MutableState<Boolean> = mutableStateOf(false)
    val listenBrainzLogin: MutableState<Boolean> = mutableStateOf(false)
    val lastfmLogin: MutableState<Boolean> = mutableStateOf(false)
    val webDavEditor: MutableState<Boolean> = mutableStateOf(false)
    val smbEditor: MutableState<Boolean> = mutableStateOf(false)
    val discordLogin: MutableState<Boolean> = mutableStateOf(false)
    val discordDialog: MutableState<DiscordDialog?> = mutableStateOf(null)
    /** The playlist being rearranged, or null when the reorder sheet is shut. */
    val reorderTarget: MutableState<UserPlaylist?> = mutableStateOf(null)
    /** Its entries as YouTube has them now: fetched fresh when the sheet opens. */
    val reorderEntries: MutableState<UiState<List<Song>>> = mutableStateOf(UiState.Loading)
    val reorderSaving: MutableState<Boolean> = mutableStateOf(false)
    val songSortMenuOpen: MutableState<Boolean> = mutableStateOf(false)
}

/**
 * The root's dialog blocks, in the order the root drew them (later ones over earlier ones). [detailBrowseId] and [songSort] are
 * the open release page's id and track order (the sort menu), [onOpenLibrary] shows the Library tab after a sign-in, and
 * [onOpenSettings] opens Settings from the account switcher.
 */
@Composable
internal fun PhoneRootDialogs(
    dialogs: PhoneRootDialogState,
    hazeState: HazeState,
    viewModel: MainViewModel,
    scope: CoroutineScope,
    googleAccounts: List<GoogleAccountSession>,
    activeAccountId: String?,
    activeProfileId: String?,
    listenBrainzToken: String,
    detailBrowseId: String?,
    songSort: SongSort,
    showUpdateDialog: Boolean,
    onShowUpdateDialogChange: (Boolean) -> Unit,
    updateNotice: AppUpdateChecker.UpdateInfo?,
    roomLauncher: RoomLauncher?,
    showQueueNotice: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    var webSession by dialogs.webSession
    var showSpotifyCanvasAuth by dialogs.spotifyCanvasAuth
    var showSpotifyLogin by dialogs.spotifyLogin
    var showSignatureApi by dialogs.signatureApi
    var showWearOs by dialogs.wearOs
    var editingSource by dialogs.editingSource
    var confirmJioSaavn by dialogs.confirmJioSaavn
    var editingPartyServer by dialogs.editingPartyServer
    var showLyricsSources by dialogs.lyricsSources
    var showAppLanguage by dialogs.appLanguage
    var showTranslationLanguage by dialogs.translationLanguage
    var showAccountSelector by dialogs.accountSelector
    var showListenBrainzLogin by dialogs.listenBrainzLogin
    var showLastfmLogin by dialogs.lastfmLogin
    var showWebDavEditor by dialogs.webDavEditor
    var showSmbEditor by dialogs.smbEditor
    var showDiscordLogin by dialogs.discordLogin
    var discordDialog by dialogs.discordDialog
    var reorderTarget by dialogs.reorderTarget
    val reorderEntries by dialogs.reorderEntries
    var reorderSaving by dialogs.reorderSaving
    var songSortMenuOpen by dialogs.songSortMenuOpen

    // ---- Reorder playlist ----
    // Full height, and the list never hands its leftover scroll to the
    // sheet: every vertical drag inside it is meant for a row or the list,
    // and one that pulled the sheet down instead would throw the new order
    // away. (Material3 1.3 has no sheetGesturesEnabled; the row drags
    // consume their own events, so the list's overscroll is the only path.)
    reorderTarget?.let { target ->
        val close = { reorderTarget = null }
        val keepSheetStill = remember {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = available
                override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available
            }
        }
        com.cosmotune.music.ui.components.OrbitSheetHost(onDismiss = close) {
            ReorderPlaylistSheet(
                modifier = Modifier.nestedScroll(keepSheetStill),
                playlist = target,
                entries = reorderEntries,
                saving = reorderSaving,
                onClose = close,
                onSave = { reordered ->
                    val original = (reorderEntries as? UiState.Success)?.data.orEmpty()
                    reorderSaving = true
                    viewModel.reorderPlaylist(target, original, reordered) { saved ->
                        reorderSaving = false
                        showQueueNotice(
                            context.getString(if (saved) R.string.playlist_reordered else R.string.reorder_failed),
                        )
                        if (saved && reorderTarget == target) reorderTarget = null
                    }
                },
            )
        }
    }

    // ---- The sign-in expired and could not be renewed silently: "Sign in again" ----
    if (webSession == null) {
        com.cosmotune.ui.account.SessionExpiredBannerHost(
            onSignIn = { webSession = WebSessionMode.SIGN_IN },
            modifier = Modifier.statusBarsPadding().padding(top = 56.dp),
        )
    }

    // ---- Google sign-in (full screen WebView) ----
    webSession?.let { mode ->
        val signInAccount = googleAccounts.firstOrNull { it.accountId == activeAccountId }
        com.cosmotune.music.auth.YouTubeSignInOverlay(
            mode = mode,
            viewModel = viewModel,
            initialCookie = if (mode == WebSessionMode.SWITCH_CHANNEL) signInAccount?.cookie else null,
            initialGoogleCookie = if (mode == WebSessionMode.SWITCH_CHANNEL) signInAccount?.googleCookie else null,
            onClose = { webSession = null },
            onSignedIn = {
                webSession = null
                if (mode == WebSessionMode.SIGN_IN) onOpenLibrary()
            },
        )
    }

    // ---- Update available, then What's new (the first launch after an update): PhoneUpdateSheets ----
    PhoneUpdateSheets(showUpdateDialog, onShowUpdateDialogChange, updateNotice, hazeState)

    if (showLyricsSources) {
        BackHandler { showLyricsSources = false }
        LyricsSourcesDialog(
            hazeState = hazeState,
            onDismiss = { showLyricsSources = false },
        )
    }

    if (songSortMenuOpen) {
        BackHandler { songSortMenuOpen = false }
        FrostedSortMenu(
            hazeState = hazeState,
            selected = songSort,
            onSelect = { option ->
                detailBrowseId?.let { AppSettings.setDetailSongSort(it, option) }
                songSortMenuOpen = false
            },
            // Flipping the date direction deliberately leaves the menu up:
            // closing it here would cut the arrow's rotation animation off
            // before it played, and the open menu lets the direction flip
            // read against the list reordering behind the frost.
            onFlipDateDirection = {
                detailBrowseId?.let { browseId ->
                    val next = when (songSort) {
                        SongSort.DATE_ADDED_DESC -> SongSort.DATE_ADDED_ASC
                        SongSort.DATE_ADDED_ASC -> SongSort.DATE_ADDED_DESC
                        else -> SongSort.DATE_ADDED_DESC
                    }
                    AppSettings.setDetailSongSort(browseId, next)
                }
            },
            onDismiss = { songSortMenuOpen = false },
        )
    }

    if (showAccountSelector) {
        BackHandler { showAccountSelector = false }
        AccountProfileSelector(
            accounts = googleAccounts,
            activeAccountId = activeAccountId,
            activeProfileId = activeProfileId,
            hazeState = hazeState,
            onSelect = { selected, profile -> viewModel.selectProfile(selected.accountId, profile.profileId) },
            onAddAccount = {
                showAccountSelector = false
                webSession = WebSessionMode.SIGN_IN
            },
            onRemoveAccount = { selected ->
                viewModel.removeAccount(selected.accountId)
                showAccountSelector = false
            },
            onOpenSettings = {
                showAccountSelector = false
                onOpenSettings()
            },
            onDismiss = { showAccountSelector = false },
        )
    }

    if (showAppLanguage) {
        BackHandler { showAppLanguage = false }
        AppLanguageDialog(
            hazeState = hazeState,
            onDismiss = { showAppLanguage = false },
        )
    }

    if (showTranslationLanguage) {
        BackHandler { showTranslationLanguage = false }
        TranslationLanguageDialog(
            hazeState = hazeState,
            onDismiss = { showTranslationLanguage = false },
        )
    }

    if (showListenBrainzLogin) {
        var tokenInput by remember { mutableStateOf(listenBrainzToken) }
        ListenBrainzTokenAlert(
            hazeState = hazeState,
            tokenInput = tokenInput,
            onTokenInputChange = { tokenInput = it },
            onSave = {
                AppSettings.setListenBrainzToken(tokenInput.trim())
                showListenBrainzLogin = false
            },
            onDismiss = { showListenBrainzLogin = false },
        )
    }

    if (showLastfmLogin) {
        var usernameInput by remember { mutableStateOf("") }
        var passwordInput by remember { mutableStateOf("") }
        var lastfmError by remember { mutableStateOf<String?>(null) }
        var lastfmLoading by remember { mutableStateOf(false) }
        LastfmLoginAlert(
            hazeState = hazeState,
            usernameInput = usernameInput,
            onUsernameInputChange = { usernameInput = it },
            passwordInput = passwordInput,
            onPasswordInputChange = { passwordInput = it },
            error = lastfmError,
            loading = lastfmLoading,
            onSignIn = {
                lastfmLoading = true
                lastfmError = null
                scope.launch {
                    try {
                        LastFM.initialize(
                            apiKey = AppSettings.lastfmApiKey.value,
                            secret = AppSettings.lastfmSecret.value,
                        )
                        LastFM.getMobileSession(usernameInput.trim(), passwordInput)
                            .onSuccess { auth ->
                                AppSettings.setLastfmSessionKey(auth.session.key)
                                AppSettings.setLastfmUsername(auth.session.name)
                                AppSettings.setLastfmEnabled(true)
                                showLastfmLogin = false
                            }
                            .onFailure { e ->
                                lastfmError = e.message ?: context.getString(R.string.login_failed)
                            }
                    } catch (e: Exception) {
                        lastfmError = e.message ?: context.getString(R.string.login_failed)
                    } finally {
                        lastfmLoading = false
                    }
                }
            },
            onDismiss = { if (!lastfmLoading) showLastfmLogin = false },
        )
    }

    if (showWebDavEditor) {
        BackHandler { showWebDavEditor = false }
        ServerEditorHost(
            hazeState = hazeState,
            title = stringResource(R.string.webdav),
            description = stringResource(R.string.webdav_description),
            fields = listOf(
                FieldConfig(
                    initial = AppSettings.webdavUrl.value,
                    placeholder = stringResource(R.string.webdav_server_url_hint),
                    keyboardType = KeyboardType.Uri,
                ),
                FieldConfig(
                    initial = AppSettings.webdavUsername.value,
                    placeholder = stringResource(R.string.username),
                ),
                FieldConfig(
                    initial = AppSettings.webdavPassword.value,
                    placeholder = stringResource(R.string.password),
                    keyboardType = KeyboardType.Password,
                    isPassword = true,
                ),
            ),
            canSubmit = { it[0].isNotBlank() },
            testFailedRes = R.string.webdav_test_failed,
            onTest = { (url, username, password) ->
                com.cosmotune.music.data.webdav.WebDavRepository.testConnection(
                    url.trim(),
                    username.trim(),
                    password,
                )
            },
            onSave = { (url, username, password) ->
                AppSettings.setWebDavUrl(url.trim())
                AppSettings.setWebDavUsername(username.trim())
                AppSettings.setWebDavPassword(password)
                showWebDavEditor = false
            },
            onDismiss = { showWebDavEditor = false },
        )
    }

    if (showSmbEditor) {
        BackHandler { showSmbEditor = false }
        ServerEditorHost(
            hazeState = hazeState,
            title = stringResource(R.string.smb),
            description = stringResource(R.string.smb_description),
            fields = listOf(
                FieldConfig(
                    initial = AppSettings.smbHost.value,
                    placeholder = stringResource(R.string.smb_server_hint),
                    keyboardType = KeyboardType.Uri,
                ),
                FieldConfig(
                    initial = AppSettings.smbShare.value,
                    placeholder = stringResource(R.string.smb_share_hint),
                ),
                FieldConfig(
                    initial = AppSettings.smbBasePath.value,
                    placeholder = stringResource(R.string.smb_folder_hint),
                ),
                FieldConfig(
                    initial = AppSettings.smbUsername.value,
                    placeholder = stringResource(R.string.username),
                ),
                FieldConfig(
                    initial = AppSettings.smbPassword.value,
                    placeholder = stringResource(R.string.password),
                    keyboardType = KeyboardType.Password,
                    isPassword = true,
                ),
            ),
            canSubmit = { it[0].isNotBlank() && it[1].isNotBlank() },
            testFailedRes = R.string.smb_test_failed,
            onTest = { (host, share, folder, username, password) ->
                com.cosmotune.music.data.smb.SmbRepository.testConnection(
                    host.trim(),
                    share.trim(),
                    folder.trim(),
                    username.trim(),
                    password,
                )
            },
            onSave = { (host, share, folder, username, password) ->
                AppSettings.setSmbHost(host.trim())
                AppSettings.setSmbShare(share.trim())
                AppSettings.setSmbBasePath(folder.trim())
                AppSettings.setSmbUsername(username.trim())
                AppSettings.setSmbPassword(password)
                showSmbEditor = false
            },
            onDismiss = { showSmbEditor = false },
        )
    }

    // A clash mid-upload, answered here so the scrim covers the tab bar
    // and mini player like every other alert. Backing out is a skip —
    // leaving the batch suspended on a dismissed dialog would hang the
    // upload with no way to reach the question again.
    val uploadConflict by com.cosmotune.music.data.webdav.WebDavUploads.conflict.collectAsStateWithLifecycle()
    uploadConflict?.let { req ->
        var applyToAll by remember(req) { mutableStateOf(false) }
        val answer: (com.cosmotune.music.data.webdav.WebDavUploads.Choice) -> Unit = { choice ->
            req.answer.complete(
                com.cosmotune.music.data.webdav.WebDavUploads.Resolution(choice, applyToAll),
            )
        }
        BackHandler { answer(com.cosmotune.music.data.webdav.WebDavUploads.Choice.SKIP) }
        WebDavConflictAlert(
            hazeState = hazeState,
            fileName = req.fileName,
            showApplyToAll = req.remaining > 0,
            applyToAll = applyToAll,
            onApplyToAllChange = { applyToAll = it },
            onOverwrite = { answer(com.cosmotune.music.data.webdav.WebDavUploads.Choice.OVERWRITE) },
            onKeepBoth = { answer(com.cosmotune.music.data.webdav.WebDavUploads.Choice.KEEP_BOTH) },
            onSkip = { answer(com.cosmotune.music.data.webdav.WebDavUploads.Choice.SKIP) },
            onDismiss = { answer(com.cosmotune.music.data.webdav.WebDavUploads.Choice.SKIP) },
        )
    }

    // ---- Discord sign-in (full screen WebView) ----
    if (showDiscordLogin) {
        BackHandler { showDiscordLogin = false }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                SignInHeader("Sign in to Discord", onClose = { showDiscordLogin = false })
                DiscordLoginScreen(
                    onTokenCaptured = { token ->
                        AppSettings.setDiscordToken(token)
                        showDiscordLogin = false
                    },
                )
            }
        }
    }

    // ---- Spotify sign-in (full screen WebView) ----
    // Out here with Discord's rather than on the Spotify page itself: see
    // SpotifyLoginScreen for what the blurred content tree does to a WebView.
    if (showSpotifyLogin) {
        BackHandler { showSpotifyLogin = false }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                SignInHeader(stringResource(R.string.spotify_sign_in), onClose = { showSpotifyLogin = false })
                SpotifyLoginScreen(
                    modifier = Modifier.navigationBarsPadding(),
                    onConnected = { token ->
                        AppSettings.setSpotifySpdcToken(token)
                        showSpotifyLogin = false
                    },
                )
            }
        }
    }

    if (showSpotifyCanvasAuth) {
        BackHandler { showSpotifyCanvasAuth = false }
        SpotifyCanvasAuthScreen(
            onNavigateUp = { showSpotifyCanvasAuth = false }
        )
    }
    if (showSignatureApi) {
        BackHandler { showSignatureApi = false }
        com.cosmotune.music.ui.screens.SignatureApiEditor(hazeState, onDismiss = { showSignatureApi = false })
    }
    if (showWearOs) {
        com.cosmotune.music.ui.screens.WearOsSettingsDialog(onDismiss = { showWearOs = false })
    }

    discordDialog?.let { which ->
        DiscordDialogHost(
            which = which,
            hazeState = hazeState,
            onDismiss = { discordDialog = null },
        )
    }

    // At the root with the source editor, and for the same two reasons: it
    // is a haze card that has to sample the backdrop it is *not* inside,
    // and a full-window scrim that has to be full-window.
    if (editingPartyServer) {
        PartyServerEditor(
            hazeState = hazeState,
            onDismiss = { editingPartyServer = false },
        )
    }

    // ---- Room ----
    // The room screen and its icon are the root's, over every mode; a refusal or an ending while the room screen is closed is
    // said here, on the strip above the player.
    val currentRoomLauncher by rememberUpdatedState(roomLauncher)
    LaunchedEffect(Unit) {
        PhoneRooms.notices.collect { if ((currentRoomLauncher as? PhoneRoomLauncher)?.isOpen != true) showQueueNotice(it) }
    }

    editingSource?.let { config ->
        SourceEditorAlert(
            hazeState = hazeState,
            config = config,
            onDismiss = { editingSource = null },
            onSaved = { editingSource = null },
            onDelete = {
                SourceRegistry.remove(config.id)
                editingSource = null
            },
            scope = scope,
        )
    }

    if (confirmJioSaavn) {
        ConfirmationAlert(
            hazeState = hazeState,
            title = stringResource(R.string.enable_jiosaavn),
            description = stringResource(R.string.jiosaavn_mismatch_warning),
            confirmLabel = stringResource(R.string.enable_anyway),
            onConfirm = {
                SourceRegistry.configs.value
                    .firstOrNull { it.kind == SourceKind.JIOSAAVN }
                    ?.let { SourceRegistry.setEnabled(it.id, true) }
                confirmJioSaavn = false
            },
            onDismiss = { confirmJioSaavn = false },
        )
    }
}

/**
 * The head of a full-screen sign-in page (Discord, Spotify) in Orbit: the round close button of the kit, the mono "Sign in" label
 * over the [title] in the wide face, under the status bar.
 */
@Composable
private fun SignInHeader(title: String, onClose: () -> Unit) {
    val fonts = com.cosmotune.ui.orbit.orbitFonts()
    val look = com.cosmotune.ui.orbit.orbitLook()
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        com.cosmotune.ui.orbit.OrbitRoundButton(CosmoIcons.Close, stringResource(R.string.close), look.ink, onClose)
        Column(Modifier.weight(1f)) {
            com.cosmotune.ui.orbit.OrbitLabel("Sign in", fonts, look.dim)
            Text(title, style = fonts.heroTitle.copy(fontSize = 20.sp, lineHeight = 23.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


/**
 * The track-list sort menu, styled after the account switcher: a full-screen
 * scrim to catch the dismissal tap, and the options on a frosted panel that
 * blurs the page behind it. Composed here in the main hierarchy rather than
 * as a popup window — which is exactly what lets the haze see the content it
 * is blurring.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun FrostedSortMenu(
    hazeState: HazeState,
    selected: SongSort,
    onSelect: (SongSort) -> Unit,
    onFlipDateDirection: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The new look: the Orbit menu (the video view menu's popover), under the top bar's end, the one in force solid ink.
    if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) {
        val dateActive = selected == SongSort.DATE_ADDED_ASC || selected == SongSort.DATE_ADDED_DESC
        val dateLabel = stringResource(R.string.sort_date_added_toggle)
        val newest = stringResource(R.string.sort_date_added)
        val oldest = stringResource(R.string.sort_date_added_oldest)
        val labels = SongSort.entries.associateWith { it.localizedLabel() }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 52.dp, end = 16.dp).size(1.dp)) {
                com.cosmotune.ui.orbit.OrbitMenu(
                    open = true, onDismiss = onDismiss, fonts = com.cosmotune.ui.orbit.orbitFonts(), look = com.cosmotune.ui.orbit.orbitLook(),
                    header = "Sort",
                    items = buildList {
                        // Date added is one row: tapping it flips the direction and leaves the menu up, as before.
                        add(com.cosmotune.ui.orbit.OrbitMenuItem(
                            dateLabel, if (dateActive) (if (selected == SongSort.DATE_ADDED_ASC) oldest else newest) else null,
                            if (selected == SongSort.DATE_ADDED_ASC) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                            selected = dateActive, keepOpen = true, onClick = onFlipDateDirection,
                        ))
                        SongSort.entries.filter { it != SongSort.DATE_ADDED_ASC && it != SongSort.DATE_ADDED_DESC }.forEach { option ->
                            add(com.cosmotune.ui.orbit.OrbitMenuItem(labels.getValue(option), selected = option == selected, keepOpen = true) { onSelect(option) })
                        }
                    },
                )
            }
        }
        return
    }
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = MaterialTheme.shapes.extraLarge
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = .48f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.TopEnd,
    ) {
        Surface(
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shape,
            modifier = Modifier
                .padding(top = 56.dp, end = 20.dp)
                .width(IntrinsicSize.Max)
                .clip(shape)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(MaterialTheme.colorScheme.surface)
                    } else {
                        Modifier.optimizedHazeEffect(
                            state = hazeState,
                            style = HazeMaterials.thin(MaterialTheme.colorScheme.surface),
                        )
                    },
                )
                .clickable(onClick = {}),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                // Date added is one row, Spotify-style: the arrow on it shows
                // the direction — up for newest first, down for oldest — and
                // tapping flips it, the rotation animating the flip. Up is
                // also where a fresh activation lands, newest first being the
                // point of the feature.
                val dateActive = selected == SongSort.DATE_ADDED_ASC ||
                    selected == SongSort.DATE_ADDED_DESC
                val arrowRotation by animateFloatAsState(
                    targetValue = if (selected == SongSort.DATE_ADDED_ASC) 180f else 0f,
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 200),
                    label = "dateAddedArrow",
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { onFlipDateDirection() }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.sort_date_added_toggle),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (dateActive) {
                        Icon(
                            Icons.Rounded.ArrowUpward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.rotate(arrowRotation),
                        )
                    }
                }
                SongSort.entries
                    .filter { it != SongSort.DATE_ADDED_ASC && it != SongSort.DATE_ADDED_DESC }
                    .forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .clickable(role = Role.Button) { onSelect(option) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                option.localizedLabel(),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (option == selected) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun SongSort.localizedLabel(): String = when (this) {
    SongSort.DEFAULT -> stringResource(R.string.sort_default)
    SongSort.TITLE_ASC -> stringResource(R.string.sort_title_ascending)
    SongSort.TITLE_DESC -> stringResource(R.string.sort_title_descending)
    SongSort.DATE_ADDED_ASC -> stringResource(R.string.sort_date_added_oldest)
    SongSort.DATE_ADDED_DESC -> stringResource(R.string.sort_date_added)
}

