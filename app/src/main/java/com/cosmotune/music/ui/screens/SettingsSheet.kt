package com.cosmotune.music.ui.screens

import androidx.compose.foundation.selection.selectableGroup

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOff
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.MotionPhotosOff
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Wifi
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import com.cosmotune.music.data.lyrics.translationLanguageName
import com.cosmotune.ui.bridge.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.cosmotune.ui.bridge.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.cosmotune.ui.bridge.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import com.cosmotune.music.ui.components.isGlassSupported
import com.cosmotune.music.ui.components.languageDisplayNameRes
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.music.ui.components.SearchField
import com.cosmotune.music.ui.components.thumbnailBorder
import com.cosmotune.music.ui.icons.CosmotuneIcons
import com.cosmotune.music.ui.performance.resolvePerformanceRefreshRate
import com.cosmotune.music.ui.performance.supportedPerformanceRefreshRates
import com.cosmotune.ui.settings.CATEGORY_ID_PREFIX
import com.cosmotune.ui.settings.SettingsCategory
import com.cosmotune.ui.settings.SettingsCollector
import com.cosmotune.ui.settings.SettingsHighlight
import com.cosmotune.ui.settings.LocalSettingsTileColor
import com.cosmotune.ui.settings.SettingsHub
import com.cosmotune.ui.settings.SettingsHubTitle
import com.cosmotune.ui.settings.settingsCategoryTint
import com.cosmotune.ui.settings.SettingsIndex
import com.cosmotune.ui.settings.SettingsItem
import com.cosmotune.ui.settings.SettingsPageHeader
import com.cosmotune.ui.settings.SettingsSearchResults
import com.cosmotune.ui.settings.SettingsText
import com.cosmotune.ui.settings.asSearchItems
import com.cosmotune.ui.settings.settingsCategoryEntries
import com.cosmotune.ui.settings.settingsHighlight
import com.cosmotune.ui.settings.text
import com.cosmotune.ui.settings.title
import com.cosmotune.design.AppMode
import com.cosmotune.design.AppModes
import com.cosmotune.music.data.model.Account
import com.cosmotune.music.data.LocalMediaRepository
import com.cosmotune.music.data.NerdStats
import com.cosmotune.music.data.scrobbling.LastFM
import com.cosmotune.music.data.listentogether.ListenTogether
import com.cosmotune.music.data.settings.AppSettings
import com.cosmotune.music.data.settings.OutputPcmMode
import com.cosmotune.music.playback.AudioOutputStatus
import com.cosmotune.music.data.settings.AutomixPerformanceMode
import com.cosmotune.music.R
import com.cosmotune.music.data.sources.DeviceCodecs
import com.cosmotune.music.data.settings.AudioQuality
import com.cosmotune.music.data.settings.DownloadQuality
import com.cosmotune.music.data.settings.ThemeMode
import com.cosmotune.music.data.stats.Backup
import com.cosmotune.music.playback.AudioCache
import com.cosmotune.music.ui.player.fullBleedArtworkAvailable
import com.cosmotune.ui.theme.CosmoRadii
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.Locale

/**
 * Grouped settings, in the shape phones have taught people to expect: inset
 * cards of rows, a leading glyph per row, the current value on the right, and a
 * plain-language footer under any group whose effect isn't obvious from its
 * title. Anything with more than two choices opens a sheet rather than pushing
 * a row of chips into the layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** The window's width, for the gates that depend on it. */
    windowWidth: Dp,
    signedIn: Boolean,
    account: Account?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onAccountScrobbling: () -> Unit,
    onEqualizer: () -> Unit,
    onOpenReplay: () -> Unit,
    onLyricsSources: () -> Unit,
    onTranslationLanguage: () -> Unit,
    onSources: () -> Unit,
    onListenTogether: () -> Unit,
    onSpotifyCanvasAuth: () -> Unit,
    onAppLanguage: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onArtistSignatureApi: () -> Unit = {},
    onWearOs: () -> Unit = {},
    onPlayProviderSongs: ((List<com.cosmotune.music.data.model.Song>, Int) -> Unit)? = null,
    /** Playlist sync across devices: who it is signed in as and how the last sync went. */
    cloudStatus: com.cosmotune.cloud.CloudStatus = com.cosmotune.cloud.CloudStatus(),
    /** Cloud sync is opt-in: while off, the group offers only to turn it on or to sign in. */
    cloudEnabled: Boolean = false,
    onCloudTurnOn: () -> Unit = {},
    onCloudSignIn: () -> Unit = {},
    onCloudSyncNow: () -> Unit = {},
    onCloudSignOut: () -> Unit = {},
    /** Asked for only after the confirmation below. */
    onCloudDeleteAccount: () -> Unit = {},
    /** False while a page Settings opened stands over it, so back closes that page and not one of these. */
    handleBack: Boolean = true,
) {
    val context = LocalContext.current

    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()
    val crossfade by AppSettings.crossfadeSeconds.collectAsStateWithLifecycle()
    val smartFade by AppSettings.smartFadeEnabled.collectAsStateWithLifecycle()
    val automixPerformance by AppSettings.automixPerformanceMode.collectAsStateWithLifecycle()
    val skipSilence by AppSettings.skipSilence.collectAsStateWithLifecycle()
    val dolbyAtmos by AppSettings.dolbyAtmos.collectAsStateWithLifecycle()
    // A property of the hardware, so it is read once rather than remembered
    // against a key that can never change — see [DeviceCodecs.playsDolbyAtmos],
    // which caches the codec-list walk for the life of the process.
    val dolbyAtmosSupported = DeviceCodecs.playsDolbyAtmos
    val spatialAudio by AppSettings.spatialAudio.collectAsStateWithLifecycle()
    val nerdStats by AppSettings.showNerdStats.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val liquidGlass by AppSettings.liquidGlass.collectAsStateWithLifecycle()
    val liquidGlassSupported = isGlassSupported()
    val lyricsBlur by AppSettings.lyricsBlur.collectAsStateWithLifecycle()
    val animatedCanvas by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    val signatureEnabled by AppSettings.signatureApiEnabled.collectAsStateWithLifecycle()
    val signatureUrl by AppSettings.signatureApiUrl.collectAsStateWithLifecycle()
    val openCanvasEnabled by AppSettings.openCanvasEnabled.collectAsStateWithLifecycle()
    val openCanvasResolution by AppSettings.openCanvasResolution.collectAsStateWithLifecycle()
    val canvasOverCellular by AppSettings.canvasOverCellular.collectAsStateWithLifecycle()

    val fullBleedArtwork by AppSettings.fullBleedArtwork.collectAsStateWithLifecycle()
    val legacyMeshGradient by AppSettings.legacyMeshGradient.collectAsStateWithLifecycle()
    val syncedLyrics by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val translationLanguage by AppSettings.translationLanguage.collectAsStateWithLifecycle()
    val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
    val sessionId by AppSettings.audioSessionId.collectAsStateWithLifecycle()
    val outputPcmMode by AppSettings.outputPcmMode.collectAsStateWithLifecycle()
    val preferUsbDac by AppSettings.preferUsbDac.collectAsStateWithLifecycle()
    val bitPerfectUsb by AppSettings.bitPerfectUsb.collectAsStateWithLifecycle()
    val loudnessNormalization by AppSettings.loudnessNormalization.collectAsStateWithLifecycle()
    val loudnessOffOnSpeaker by AppSettings.loudnessOffOnSpeaker.collectAsStateWithLifecycle()
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val playingFormat by NerdStats.current.collectAsStateWithLifecycle()
    val playingDolbyAtmos = playingFormat?.isDolbyAtmos == true
    val cacheLimitBytes by AppSettings.audioCacheLimitBytes.collectAsStateWithLifecycle()
    val showCacheFolder by AppSettings.showCacheFolder.collectAsStateWithLifecycle()
    val downloadQuality by AppSettings.downloadQuality.collectAsStateWithLifecycle()
    val wifiOnlyDownloads by AppSettings.wifiOnlyDownloads.collectAsStateWithLifecycle()
    val exportDownloads by AppSettings.exportDownloads.collectAsStateWithLifecycle()
    val stopOnTaskRemoved by AppSettings.stopOnTaskRemoved.collectAsStateWithLifecycle()
    val hideVolumeBar by AppSettings.hideVolumeBar.collectAsStateWithLifecycle()
    val hideSongStatus by AppSettings.hideSongStatus.collectAsStateWithLifecycle()
    val queueHistory by AppSettings.showQueueHistory.collectAsStateWithLifecycle()
    val hideStatusBar by AppSettings.hideStatusBarInPlayer.collectAsStateWithLifecycle()
    val swipeToPlayNext by AppSettings.swipeToPlayNext.collectAsStateWithLifecycle()
    val dontRepeatSuggestions by AppSettings.dontRepeatSuggestions.collectAsStateWithLifecycle()
    val preferMusicOnly by AppSettings.preferMusicOnly.collectAsStateWithLifecycle()
    val smartVersionAlignment by AppSettings.smartVersionAlignment.collectAsStateWithLifecycle()
    val filterNonMusicAudio by AppSettings.filterNonMusicAudio.collectAsStateWithLifecycle()
    val localMusicFolderUri by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()
    val highPerformanceMode by AppSettings.highPerformanceMode.collectAsStateWithLifecycle()
    val performanceRefreshRate by AppSettings.performanceRefreshRate.collectAsStateWithLifecycle()
    val currentDisplay = LocalView.current.display
    val supportedRefreshRates = remember(currentDisplay) {
        currentDisplay.supportedPerformanceRefreshRates()
    }
    val selectedPerformanceRefreshRate = remember(currentDisplay, performanceRefreshRate) {
        currentDisplay.resolvePerformanceRefreshRate(performanceRefreshRate)
    }

    LaunchedEffect(selectedPerformanceRefreshRate, performanceRefreshRate) {
        if (selectedPerformanceRefreshRate != performanceRefreshRate) {
            AppSettings.setPerformanceRefreshRate(selectedPerformanceRefreshRate)
        }
    }

    // Scrobbling states
    val lastfmEnabled by AppSettings.lastfmEnabled.collectAsStateWithLifecycle()
    val lastfmUsername by AppSettings.lastfmUsername.collectAsStateWithLifecycle()
    val lastfmSessionKey by AppSettings.lastfmSessionKey.collectAsStateWithLifecycle()
    val lastfmScrobbleEnabled by AppSettings.lastfmScrobbleEnabled.collectAsStateWithLifecycle()
    val lastfmNowPlayingEnabled by AppSettings.lastfmNowPlaying.collectAsStateWithLifecycle()
    val scrobbleMinDuration by AppSettings.scrobbleMinDuration.collectAsStateWithLifecycle()
    val scrobbleDelayPercent by AppSettings.scrobbleDelayPercent.collectAsStateWithLifecycle()
    val scrobbleDelaySeconds by AppSettings.scrobbleDelaySeconds.collectAsStateWithLifecycle()
    val listenBrainzEnabled by AppSettings.listenBrainzEnabled.collectAsStateWithLifecycle()
    val listenBrainzToken by AppSettings.listenBrainzToken.collectAsStateWithLifecycle()

    val replayGenres by AppSettings.replayGenres.collectAsStateWithLifecycle()

    // Read here so the row can say "In a party · ABC123" rather than making
    // somebody open the screen to find out whether they are still in one.
    val party by ListenTogether.state.collectAsStateWithLifecycle()

    // Filters the rows below — see [SettingsSearch]. Blank shows everything,
    // exactly as if the field weren't there.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.ALL) }
    var picking by remember { mutableStateOf<QualityTarget?>(null) }
    var pickingDownloadQuality by remember { mutableStateOf(false) }
    var pickingAutomixPerformance by remember { mutableStateOf(false) }
    // What the last export or import did, shown on the row that did it rather
    // than as a toast: a backup is the one action here whose outcome nobody can
    // check by looking at the app afterwards. Held per direction, or an import's
    // result reports itself under the word "Export".
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf(false) }
    var confirmClearSongCache by remember { mutableStateOf(false) }
    var confirmCloudDelete by remember { mutableStateOf(false) }
    var showPerformanceWarning by remember { mutableStateOf(false) }
    var showPerformanceConfirmation by remember { mutableStateOf(false) }
    val backupScope = rememberCoroutineScope()

    val batterySettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        showPerformanceConfirmation = true
    }
    val localMusicFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { folder ->
        if (folder == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                folder,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        AppSettings.setLocalMusicFolderUri(folder.toString())
    }

    /**
     * Both halves go through the system document picker rather than a path of
     * this app's own choosing. That is what puts the file somewhere the user can
     * actually find it — Drive, Files, a folder they already back up — and it
     * means neither direction needs a storage permission, since the grant
     * arrives with the document they picked.
     */
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            exportStatus = Backup.exportTo(context, target).fold(
                onSuccess = { months ->
                    context.getString(R.string.export_succeeded, context.countOfMonths(months))
                },
                onFailure = {
                    context.getString(R.string.export_failed, it.message ?: context.getString(R.string.unknown_error))
                },
            )
        }
    }
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            importStatus = Backup.importFrom(context, source).fold(
                onSuccess = {
                    context.getString(R.string.import_succeeded, context.countOfMonths(it.months), it.from)
                },
                onFailure = {
                    context.getString(R.string.import_failed, it.message ?: context.getString(R.string.unknown_error))
                },
            )
        }
    }
    var showListenBrainzTokenDialog by remember { mutableStateOf(false) }
    var showLastfmLoginDialog by remember { mutableStateOf(false) }
    val scrobbleScope = rememberCoroutineScope()

    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    // The settings home (no category picked) lists the categories, each with a line of its current values; a category is a page
    // of grouped cards. While the search field has text, every page runs once more in a pass that draws nothing: each row
    // registers itself with [SettingsCollector] (its title, its keywords, its summary), and the results are listed in place of
    // the categories. Picking one opens its page, scrolls to the row and lights it up ([SettingsHighlight]).
    val highlight = remember { SettingsHighlight() }
    val collector = if (searchQuery.isNotBlank()) remember(searchQuery) { SettingsCollector() } else null
    val search = SettingsSearch(selectedCategory, collector, highlight)
    val onHub = selectedCategory == SettingsCategory.ALL

    // Back leaves the search, then the page, before it leaves Settings. Off while a page Settings opened is over it.
    BackHandler(enabled = handleBack && (!onHub || searchQuery.isNotBlank())) {
        if (searchQuery.isNotBlank()) searchQuery = "" else selectedCategory = SettingsCategory.ALL
    }

    // What is left after a keystroke is a different list, and the offset the
    // last one was scrolled to means nothing in it.
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    var isFirstSearchComposition by remember { mutableStateOf(true) }
    LaunchedEffect(searchQuery, selectedCategory) {
        if (isFirstSearchComposition && searchQuery.isEmpty()) {
            // Skip the initial composition during Activity recreation:
            // rememberSaveable already restored the scroll position.
            isFirstSearchComposition = false
        } else {
            isFirstSearchComposition = false
            scrollState.scrollTo(0)
        }
    }

    val cacheUnlimited = cacheLimitBytes == AppSettings.UNLIMITED_CACHE_LIMIT_BYTES
    // The slider's last stop, one step past the largest fixed size, is
    // "Unlimited" — see [AppSettings.setAudioCacheLimitBytes].
    val cacheLimitMb = if (cacheUnlimited) CACHE_UNLIMITED_STOP_MB else (cacheLimitBytes / (1024 * 1024)).toInt()
    val unlimitedLabel = stringResource(R.string.song_cache_unlimited)
    val cacheLimitLabel = if (cacheUnlimited) unlimitedLabel else formatCacheSize(cacheLimitMb)

    val hubEntries = settingsCategoryEntries(
        summaries = mapOf(
            SettingsCategory.ACCOUNT to (
                account?.email?.takeIf { it.isNotBlank() }
                    ?: stringResource(if (signedIn) R.string.signed_in else R.string.not_signed_in)
                ),
            SettingsCategory.PLAYBACK to "${stringResource(R.string.wifi)}: ${wifiQuality.localizedLabel()} · " +
                "${stringResource(R.string.mobile_data)}: ${cellularQuality.localizedLabel()}",
            SettingsCategory.SOURCES to stringResource(R.string.settings_sources_summary),
            SettingsCategory.DOWNLOADS to "${downloadQuality.localizedLabel()} · $cacheLimitLabel",
            SettingsCategory.LYRICS to listOfNotNull(
                stringResource(R.string.synced_lyrics).takeIf { syncedLyrics },
                stringResource(R.string.animated_cover_art).takeIf { animatedCanvas },
                "OpenCanvas".takeIf { animatedCanvas && openCanvasEnabled },
            ).joinToString(" · "),
            SettingsCategory.APPEARANCE to theme.localizedLabel(),
            SettingsCategory.INTEGRATIONS to party.code?.let { stringResource(R.string.listen_together_in_party, it) },
            SettingsCategory.PRIVACY to (exportStatus ?: importStatus),
            SettingsCategory.ABOUT to "Cosmotune $version",
        ),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(contentPadding),
    ) {
        if (onHub) {
            SettingsHubTitle(stringResource(R.string.settings))
            com.cosmotune.ui.settings.SettingsSearchPill(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = stringResource(R.string.settings_search_hint),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
            if (collector == null) {
                SettingsHub(
                    entries = hubEntries,
                    onOpen = { selectedCategory = it },
                    // Who is signed in comes first, as a card of its own: it opens the account and its services, or signs in.
                    accountCard = {
                        AccountCard(
                            signedIn = signedIn,
                            account = account,
                            onSignIn = onSignIn,
                            onClick = onAccountScrobbling,
                            syncLine = if (cloudEnabled) cloudStatusLine(cloudStatus) else stringResource(R.string.cloud_sync_off),
                        )
                    },
                )
            }
        } else {
            SettingsPageHeader(
                title = selectedCategory.title(),
                // In the new look the top bar's back is the one back button: it goes back like the system's (to the settings home).
                onBack = if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) null else { { selectedCategory = SettingsCategory.ALL } },
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        // A page's rows take its category's colour in their icon tiles, as the settings home shows it.
        val pageTint = settingsCategoryTint(selectedCategory)
        CompositionLocalProvider(LocalSettingsTileColor provides pageTint.takeIf { !onHub }) {
        // ---- Account & sync ----
        SearchableSettingsGroup(search, category = SettingsCategory.ACCOUNT) {
            val accountTitle = stringResource(R.string.account_integrations)
            // What the row opens is the scrobbling screen, so the services it
            // signs into are worth typing at this field even though none of
            // them is named on the row itself.
            row(accountTitle, "scrobbling", "last.fm", "listenbrainz") {
                SettingsRow(
                    icon = Icons.Rounded.Person,
                    title = accountTitle,
                    subtitle = account?.email?.takeIf { it.isNotBlank() }
                        ?: stringResource(if (signedIn) R.string.signed_in else R.string.not_signed_in),
                    onClick = onAccountScrobbling,
                )
            }
            // Library sync (playlists, likes, followed artists). Opt-in: nothing is sent and no account exists until the person
            // turns it on (it then syncs as a guest on this device) or signs in with Google, which is what lets another device
            // find the same library.
            val cloudGoogle = cloudEnabled && cloudStatus.signedIn && !cloudStatus.isGuest
            val cloudTitle = stringResource(R.string.cloud_sync)
            val cloudSummary = if (cloudEnabled) cloudStatusLine(cloudStatus) else stringResource(R.string.cloud_sync_off)
            row(cloudTitle, "playlists", "likes", "artists", "backup", "devices", "firebase", summary = cloudSummary) {
                SettingsRow(
                    icon = Icons.Rounded.CloudSync,
                    title = cloudTitle,
                    subtitle = cloudSummary,
                )
            }
            if (!cloudEnabled) {
                val cloudTurnOnTitle = stringResource(R.string.cloud_turn_on)
                row(cloudTurnOnTitle, "cloud", "sync", "enable", "playlists") {
                    SettingsRow(
                        icon = Icons.Rounded.Sync,
                        title = cloudTurnOnTitle,
                        subtitle = stringResource(R.string.cloud_turn_on_subtitle),
                        onClick = onCloudTurnOn,
                    )
                }
            }
            if (!cloudGoogle || cloudStatus.needsSignIn) {
                val cloudSignInTitle = stringResource(R.string.cloud_sign_in_google)
                row(cloudSignInTitle, "google", "account", "sync") {
                    SettingsRow(
                        icon = Icons.AutoMirrored.Rounded.Login,
                        title = cloudSignInTitle,
                        subtitle = stringResource(R.string.cloud_sign_in_google_subtitle),
                        onClick = onCloudSignIn,
                    )
                }
            }
            if (cloudEnabled) {
                val cloudSyncNowTitle = stringResource(R.string.cloud_sync_now)
                row(cloudSyncNowTitle, "cloud", "playlists") {
                    SettingsRow(
                        icon = Icons.Rounded.Sync,
                        title = cloudSyncNowTitle,
                        enabled = !cloudStatus.syncing,
                        onClick = onCloudSyncNow,
                    )
                }
            }
            if (cloudGoogle) {
                val cloudSignOutTitle = stringResource(R.string.cloud_sign_out)
                row(cloudSignOutTitle, "google", "cloud") {
                    SettingsRow(
                        icon = Icons.AutoMirrored.Rounded.Logout,
                        title = cloudSignOutTitle,
                        onClick = onCloudSignOut,
                    )
                }
            }
            if (cloudEnabled && cloudStatus.signedIn) {
                val cloudDeleteTitle = stringResource(R.string.cloud_delete)
                row(cloudDeleteTitle, "cloud", "erase", "privacy") {
                    SettingsRow(
                        icon = Icons.Rounded.DeleteForever,
                        title = cloudDeleteTitle,
                        onClick = { confirmCloudDelete = true },
                    )
                }
            }
        }

        // ---- Playback & audio quality ----
        // The row that used to sit at the top of this group was called
        // "Lossless / HQ Audio" and toggled `SourceRegistry.setModuleEnabled` —
        // it switched the *module source* on and off, not lossless. Sources
        // above lists that as the module's own row now. Lossless itself is no
        // longer a setting at all — see
        // [SourceResolver.requestForNow][com.cosmotune.music.data.sources.SourceResolver.requestForNow].
        SearchableSettingsGroup(search, category = SettingsCategory.PLAYBACK, header = stringResource(R.string.audio_quality)) {
            val onWifiTitle = stringResource(R.string.on_wifi)
            row(onWifiTitle, "wi-fi", "streaming quality") {
                SettingsRow(
                    icon = Icons.Rounded.Wifi,
                    title = onWifiTitle,
                    badge = stringResource(R.string.in_use).takeIf { metered == false },
                    value = wifiQuality.localizedLabel(),
                    onClick = { picking = QualityTarget.WIFI },
                )
            }
            val onMobileDataTitle = stringResource(R.string.on_mobile_data)
            row(onMobileDataTitle, "cellular", "streaming quality") {
                SettingsRow(
                    icon = Icons.Rounded.SignalCellularAlt,
                    title = onMobileDataTitle,
                    badge = stringResource(R.string.in_use).takeIf { metered == true },
                    value = cellularQuality.localizedLabel(),
                    onClick = { picking = QualityTarget.CELLULAR },
                )
            }
            // Sits with the quality ceilings rather than with Playback: it
            // decides which version of a track gets fetched, the same question
            // the two rows above answer, and not how one is played back.
            //
            // Greyed rather than hidden where the device can't decode E-AC-3.
            // A missing row reads as a feature the app doesn't have; a disabled
            // one with a reason under it is the difference between "Cosmotune
            // has no Atmos" and "this phone has no Dolby decoder", and only the
            // second is true. The stored preference is left untouched either
            // way — see [AppSettings.dolbyAtmos].
            val dolbyAtmosTitle = stringResource(R.string.dolby_atmos)
            row(dolbyAtmosTitle, "surround", "e-ac-3", summary = stringResource(R.string.dolby_atmos_subtitle)) {
                SettingsRow(
                    iconPainter = painterResource(R.drawable.ic_dolby_atmos),
                    title = dolbyAtmosTitle,
                    subtitle = stringResource(
                        if (dolbyAtmosSupported) {
                            R.string.dolby_atmos_subtitle
                        } else {
                            R.string.dolby_atmos_unavailable
                        },
                    ),
                    enabled = dolbyAtmosSupported,
                    trailing = {
                        Switch(
                            checked = dolbyAtmos && dolbyAtmosSupported,
                            onCheckedChange = AppSettings::setDolbyAtmos,
                            enabled = dolbyAtmosSupported,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setDolbyAtmos(!dolbyAtmos) },
                )
            }
        }
        SearchableSettingsGroup(search, category = SettingsCategory.PLAYBACK, header = SettingsText.GROUP_OUTPUT.text()) {
            val outputPrecisionTitle = stringResource(R.string.output_precision)
            row(outputPrecisionTitle, "pcm", "bit depth", "sample rate", "dac") {
                SettingsRow(
                    icon = Icons.Rounded.GraphicEq,
                    title = outputPrecisionTitle,
                    subtitle = buildString {
                        append(outputStatus.sink)
                        append(" · ")
                        append(outputStatus.deviceName)
                        (outputStatus.actualSampleRateHz ?: outputStatus.sampleRatesHz.firstOrNull())
                            ?.let { append(" · ${it / 1000.0} kHz") }
                        append(" · ")
                        append(AudioOutputStatus.encodingLabel(outputStatus))
                    },
                )
                SegmentedControl(
                    options = OutputPcmMode.entries.map(OutputPcmMode::label),
                    selectedIndex = OutputPcmMode.entries.indexOf(outputPcmMode),
                    onSelect = { AppSettings.setOutputPcmMode(OutputPcmMode.entries[it]) },
                    modifier = Modifier.padding(start = TEXT_INSET, end = ROW_INSET, bottom = 14.dp),
                )
            }
            val preferUsbDacTitle = stringResource(R.string.prefer_usb_dac)
            row(preferUsbDacTitle, "headphone", "output") {
                SettingsSubRow(
                    title = preferUsbDacTitle,
                    checked = preferUsbDac,
                    onCheckedChange = AppSettings::setPreferUsbDac,
                    badge = stringResource(R.string.connected).takeIf { outputStatus.isUsb },
                )
            }
            val bitPerfectTitle = stringResource(R.string.bit_perfect_usb)
            row("Listening device", "headphone", "buds", "ldac", "bluetooth", "profile", "dac") {
                com.cosmotune.music.ui.player.AndroidListeningDeviceEntry()
            }
            row(bitPerfectTitle, "bit-perfect", "bitperfect", "usb", "dac", "output", summary = stringResource(R.string.bit_perfect_usb_subtitle)) {
                SettingsSubRow(
                    title = bitPerfectTitle,
                    checked = bitPerfectUsb,
                    onCheckedChange = AppSettings::setBitPerfectUsb,
                    // What Android actually did, not what was asked for.
                    subtitle = listOfNotNull(
                        outputStatus.bitPerfect.detail.takeIf { bitPerfectUsb },
                        stringResource(R.string.bit_perfect_usb_subtitle),
                    ).joinToString(" · "),
                )
            }
        }
        SearchableSettingsGroup(search, category = SettingsCategory.PLAYBACK, header = SettingsText.GROUP_SOUND.text()) {
            // The system panel is not listed here as well. A device with a
            // Dolby or Dirac panel has something Cosmotune cannot reproduce and
            // keeps its row — but one level in, at the foot of the equaliser
            // screen, rather than as a second equaliser entry alongside ours.
            val equalizerTitle = stringResource(R.string.equalizer)
            row(equalizerTitle, "eq", "bass", "treble", summary = stringResource(R.string.equalizer_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.Tune,
                    title = equalizerTitle,
                    subtitle = stringResource(R.string.equalizer_subtitle),
                    onClick = onEqualizer,
                )
            }
            val loudnessTitle = stringResource(R.string.loudness_normalization)
            row(loudnessTitle, "loudness", "volume", "normalize", "replaygain", "lufs", summary = stringResource(R.string.loudness_normalization_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.VolumeUp,
                    title = loudnessTitle,
                    subtitle = stringResource(R.string.loudness_normalization_subtitle),
                    trailing = {
                        Switch(
                            checked = loudnessNormalization,
                            onCheckedChange = AppSettings::setLoudnessNormalization,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setLoudnessNormalization(!loudnessNormalization) },
                )
            }
            val loudnessSpeakerTitle = stringResource(R.string.loudness_off_on_speaker)
            row(loudnessSpeakerTitle, "loudness", "speaker", "normalize") {
                SettingsSubRow(
                    title = loudnessSpeakerTitle,
                    checked = loudnessOffOnSpeaker,
                    onCheckedChange = AppSettings::setLoudnessOffOnSpeaker,
                )
            }
            val skipSilenceTitle = stringResource(R.string.skip_silence)
            row(skipSilenceTitle, "silence", summary = stringResource(R.string.skip_silence_subtitle)) {
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.VolumeOff,
                    title = skipSilenceTitle,
                    // Silence skipping is Media3's own processor, and
                    // `DefaultAudioSink.configure` appends that processor list
                    // only on its 16-bit branch — the float branch gets the
                    // format converter and nothing else. So on a float track
                    // this switch really does nothing, and the row says so
                    // rather than letting someone toggle it and wonder. The
                    // equaliser is unaffected: it runs upstream of that sink.
                    subtitle = if (outputStatus.actualEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        stringResource(R.string.skip_silence_float_subtitle)
                    } else {
                        stringResource(R.string.skip_silence_subtitle)
                    },
                    trailing = {
                        Switch(
                            checked = skipSilence,
                            onCheckedChange = AppSettings::setSkipSilence,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setSkipSilence(!skipSilence) },
                )
            }
            val platformSpatial by com.cosmotune.music.playback.AndroidListeningDevices.spatial.collectAsStateWithLifecycle()
            val spatialAudioTitle = stringResource(R.string.stereo_widening)
            row(spatialAudioTitle, "surround", "3d", summary = stringResource(R.string.stereo_widening_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.SurroundSound,
                    title = spatialAudioTitle,
                    // Widening a JOC stream would fight the object-based mix
                    // Dolby has already spatialized, so the service holds this
                    // off for the duration of an Atmos track — see
                    // `PlaybackService.applySpatialAudioEnabled`. Said on the
                    // row because the switch stays where the listener left it,
                    // and a switch reading "on" over an effect that is not
                    // running is the same silent lie the equaliser screen used
                    // to tell.
                    subtitle = if (playingDolbyAtmos) {
                        stringResource(R.string.stereo_widening_atmos_paused)
                    } else if (platformSpatial.blocksWidening) {
                        stringResource(R.string.stereo_widening_system_paused)
                    } else {
                        stringResource(R.string.stereo_widening_subtitle)
                    },
                    trailing = {
                        Switch(
                            checked = spatialAudio,
                            onCheckedChange = AppSettings::setSpatialAudio,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setSpatialAudio(!spatialAudio) },
                )
            }
            // The style of the switch above and the rest of the options first seen in Mochy Music (the shared rows of the Sound page).
            row(com.cosmotune.ui.settings.SoundExtrasText.SPATIAL_STYLE, "crossfeed", "virtual speakers", "mono", "spatial", summary = com.cosmotune.ui.settings.SoundExtrasText.SPATIAL_STYLE_LINE) {
                com.cosmotune.ui.settings.SpatialStyleRow()
            }
            row(com.cosmotune.ui.settings.SoundExtrasText.SURROUND, "5.1", "7.1", "binaural", "surround", summary = com.cosmotune.ui.settings.SoundExtrasText.SURROUND_LINE) {
                com.cosmotune.ui.settings.BinauralSurroundRow()
            }
            row(com.cosmotune.ui.settings.SoundExtrasText.MAX_VOLUME, "volume cap", "hearing", "limit", summary = com.cosmotune.ui.settings.SoundExtrasText.MAX_VOLUME_LINE) {
                com.cosmotune.ui.settings.MaxVolumeRow()
            }
            row(com.cosmotune.ui.settings.SoundExtrasText.PITCH, "key", "semitone", "transpose", summary = com.cosmotune.ui.settings.SoundExtrasText.PITCH_LINE) {
                com.cosmotune.ui.settings.PitchRow()
            }
        }
        SearchableSettingsGroup(search, category = SettingsCategory.PLAYBACK, header = com.cosmotune.ui.settings.SoundExtrasText.GROUP_LYRICS_TIMING) {
            row(com.cosmotune.ui.settings.SoundExtrasText.AUTO_OFFSET, "lyrics", "sync", "offset", summary = com.cosmotune.ui.settings.SoundExtrasText.AUTO_OFFSET_LINE) {
                com.cosmotune.ui.settings.AutoLyricsOffsetRow()
            }
            row(com.cosmotune.ui.settings.SoundExtrasText.FORGET_OFFSETS, "lyrics", "offset", "reset", summary = com.cosmotune.ui.settings.SoundExtrasText.FORGET_OFFSETS_LINE) {
                com.cosmotune.ui.settings.ForgetLyricsOffsetsRow()
            }
        }
        SearchableSettingsGroup(search, category = SettingsCategory.PLAYBACK, header = com.cosmotune.ui.settings.SoundExtrasText.GROUP_NETWORK) {
            row(com.cosmotune.ui.settings.SoundExtrasText.OFFLINE, "offline", "internet", "airplane", summary = com.cosmotune.ui.settings.SoundExtrasText.OFFLINE_LINE) {
                com.cosmotune.ui.settings.OfflineModeRow()
            }
            row(com.cosmotune.ui.settings.SoundExtrasText.PROXY, "proxy", "socks", "http", summary = com.cosmotune.ui.settings.SoundExtrasText.PROXY_LINE) {
                com.cosmotune.ui.settings.ProxyRows()
            }
        }
        SearchableSettingsGroup(search, category = SettingsCategory.PLAYBACK, header = com.cosmotune.ui.settings.SoundExtrasText.GROUP_RECOGNITION) {
            row(com.cosmotune.ui.settings.SoundExtrasText.RECOGNITION, "shazam", "recognition", "identify", "microphone", summary = com.cosmotune.ui.settings.SoundExtrasText.RECOGNITION_LINE) {
                // Song recognition needs the microphone; asked for only when Listen is pressed.
                val micContext = androidx.compose.ui.platform.LocalContext.current
                var micAnswer by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
                val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                ) { granted -> micAnswer?.invoke(granted); micAnswer = null }
                com.cosmotune.ui.settings.SongRecognitionRows(
                    onPlay = onPlayProviderSongs,
                    ensureMicrophone = { answer ->
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                            micContext,
                            android.Manifest.permission.RECORD_AUDIO,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            answer(true)
                        } else {
                            micAnswer = answer
                            micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    },
                )
            }
        }
        SearchableSettingsGroup(search, category = SettingsCategory.PLAYBACK, header = SettingsText.GROUP_TRANSITIONS.text()) {
            // Automix decides its own length from each pair of tracks —
            // tempo, key, structure — so it replaces the manual slider rather
            // than needing it set to anything first.
            if (!smartFade) {
                val crossfadeTitle = stringResource(R.string.crossfade)
                row(crossfadeTitle, "fade", "gapless", summary = stringResource(R.string.crossfade_subtitle)) {
                    SliderRow(
                        icon = Icons.Rounded.Waves,
                        title = crossfadeTitle,
                        subtitle = stringResource(R.string.crossfade_subtitle),
                        value = if (crossfade == 0) stringResource(R.string.off) else "${crossfade}s",
                        sliderValue = crossfade.toFloat(),
                        onSliderValue = { AppSettings.setCrossfadeSeconds(it.roundToInt()) },
                        valueRange = 0f..12f,
                        steps = 11,
                    )
                }
            }
            val automixTitle = stringResource(R.string.automix)
            row(automixTitle, "crossfade", "smart fade", "mix") {
                SettingsRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = automixTitle,
                    subtitle = if (smartFade) {
                        stringResource(R.string.automix_enabled_subtitle)
                    } else {
                        stringResource(R.string.automix_disabled_subtitle)
                    },
                    trailing = {
                        Switch(
                            checked = smartFade,
                            onCheckedChange = AppSettings::setSmartFadeEnabled,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setSmartFadeEnabled(!smartFade) },
                )
            }
            val automixPerformanceTitle = stringResource(R.string.automix_performance)
            row(automixPerformanceTitle, "cpu", "battery", summary = stringResource(R.string.automix_performance_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.Tune,
                    title = automixPerformanceTitle,
                    subtitle = stringResource(R.string.automix_performance_subtitle),
                    value = automixPerformance.localizedLabel(),
                    onClick = { pickingAutomixPerformance = true },
                )
            }
        }
        SearchableSettingsGroup(search, category = SettingsCategory.PLAYBACK, header = SettingsText.GROUP_BEHAVIOUR.text()) {
            val playNextOnSwipeTitle = stringResource(R.string.play_next_on_swipe)
            row(playNextOnSwipeTitle, "swipe", "queue") {
                SettingsRow(
                    icon = Icons.Rounded.PlaylistPlay,
                    title = playNextOnSwipeTitle,
                    subtitle = if (swipeToPlayNext) {
                        stringResource(R.string.swipe_plays_next)
                    } else {
                        stringResource(R.string.swipe_adds_to_queue)
                    },
                    trailing = {
                        Switch(
                            checked = swipeToPlayNext,
                            onCheckedChange = AppSettings::setSwipeToPlayNext,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setSwipeToPlayNext(!swipeToPlayNext) },
                )
            }
            val stopMusicOnCloseTitle = stringResource(R.string.stop_music_on_close)
            row(stopMusicOnCloseTitle, "notification", "background", summary = stringResource(R.string.stop_music_on_close_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.MusicOff,
                    title = stopMusicOnCloseTitle,
                    subtitle = stringResource(R.string.stop_music_on_close_subtitle),
                    trailing = {
                        Switch(
                            checked = stopOnTaskRemoved,
                            onCheckedChange = AppSettings::setStopOnTaskRemoved,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setStopOnTaskRemoved(!stopOnTaskRemoved) },
                )
            }
        }

        // ---- Sources & providers ----
        // The provider packages stand above the source list, outside its card: the entry draws its own.
        if (collector != null) {
            collector.add(
                SettingsItem(
                    id = PROVIDER_PACKAGES_ID,
                    title = "Provider packages",
                    category = SettingsCategory.SOURCES,
                    keywords = listOf(stringResource(R.string.sources), "providers", "Mornye", "Amazon Music", "Qobuz", "Tidal", "Deezer", "Spotify", "repository"),
                ),
            )
        } else if (selectedCategory == SettingsCategory.SOURCES) {
            Column(Modifier.fillMaxWidth().settingsHighlight(PROVIDER_PACKAGES_ID, highlight)) {
                com.cosmotune.ui.providers.ProviderPackagesEntry(onPlay = onPlayProviderSongs)
            }
        }
        SearchableSettingsGroup(search, category = SettingsCategory.SOURCES, header = stringResource(R.string.sources)) {
            val sourceTitle = stringResource(R.string.source)
            val sourceSubtitle = stringResource(R.string.sources_subtitle)
            row(sourceTitle, sourceSubtitle, "addon", "lossless", "providers", "provider packages", "packages", "Mornye", "Amazon Music", "Qobuz", "Tidal", "repository", summary = stringResource(R.string.settings_sources_summary)) {
                SettingsRow(
                    icon = Icons.Rounded.Extension,
                    title = sourceTitle,
                    subtitle = stringResource(R.string.settings_sources_summary),
                    onClick = onSources,
                )
            }
        }

        // ---- Downloads & storage ----
        // Its own group rather than rows bolted onto the two above, because a
        // download is not a third kind of connection. The ceilings answer "what
        // does this minute cost"; these answer "what am I keeping, and when may
        // it be fetched" — and those two questions only make sense read
        // together, which is what puts them side by side here.
        SearchableSettingsGroup(search, category = SettingsCategory.DOWNLOADS, header = stringResource(R.string.downloads)) {
            val downloadQualityTitle = stringResource(R.string.download_quality)
            row(downloadQualityTitle, "offline", "lossless") {
                SettingsRow(
                    icon = Icons.Rounded.Download,
                    title = downloadQualityTitle,
                    subtitle = stringResource(R.string.download_quality_subtitle, downloadQuality.perTrack),
                    value = downloadQuality.localizedLabel(),
                    onClick = { pickingDownloadQuality = true },
                )
            }
            // Reads as part of Download quality above it, not as a setting
            // of its own — same treatment as Play animated cover over
            // cellular gets under Animated cover art.
            val downloadWifiOnlyTitle = stringResource(R.string.download_wifi_only)
            row(downloadWifiOnlyTitle, "wi-fi", "cellular", divided = false) {
                SettingsSubRow(
                    title = downloadWifiOnlyTitle,
                    checked = wifiOnlyDownloads,
                    onCheckedChange = AppSettings::setWifiOnlyDownloads,
                    badge = stringResource(R.string.blocking).takeIf { wifiOnlyDownloads && metered == true },
                )
            }
            val exportDownloadsTitle = stringResource(R.string.export_compatible_downloads)
            row(exportDownloadsTitle, "music folder", divided = false) {
                SettingsSubRow(
                    title = exportDownloadsTitle,
                    checked = exportDownloads,
                    onCheckedChange = AppSettings::setExportDownloads,
                    subtitle = "Music/Cosmotune".takeIf { exportDownloads },
                )
            }
        }
        SearchableSettingsGroup(search, category = SettingsCategory.DOWNLOADS, header = stringResource(R.string.local_music)) {
            val localMusicFolderTitle = stringResource(R.string.local_music_folder)
            row(localMusicFolderTitle, "folder", "offline", "files") {
                SettingsRow(
                    icon = Icons.Rounded.Folder,
                    title = localMusicFolderTitle,
                    subtitle = LocalMediaRepository.selectedFolderLabel(localMusicFolderUri)
                        ?: stringResource(R.string.all_audio_folders),
                    onClick = { localMusicFolderPicker.launch(null) },
                )
            }
            if (localMusicFolderUri.isNotBlank()) {
                val useAllAudioFoldersTitle = stringResource(R.string.use_all_audio_folders)
                row(useAllAudioFoldersTitle, "folder", summary = stringResource(R.string.use_all_audio_folders_subtitle)) {
                    SettingsRow(
                        icon = Icons.Rounded.LibraryMusic,
                        title = useAllAudioFoldersTitle,
                        subtitle = stringResource(R.string.use_all_audio_folders_subtitle),
                        onClick = { AppSettings.setLocalMusicFolderUri("") },
                    )
                }
            }
            val filterNonMusicAudioTitle = stringResource(R.string.filter_non_music_audio)
            row(filterNonMusicAudioTitle, "podcast", "recording", summary = stringResource(R.string.filter_non_music_audio_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.FilterAlt,
                    title = filterNonMusicAudioTitle,
                    subtitle = stringResource(R.string.filter_non_music_audio_subtitle),
                    trailing = {
                        Switch(
                            checked = filterNonMusicAudio,
                            onCheckedChange = AppSettings::setFilterNonMusicAudio,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setFilterNonMusicAudio(!filterNonMusicAudio) },
                )
            }
        }
        SearchableSettingsGroup(search, category = SettingsCategory.DOWNLOADS, header = stringResource(R.string.storage)) {
            val showCacheFolderTitle = stringResource(R.string.show_cache_folder)
            row(showCacheFolderTitle, "cache", "cached songs", "library", "folder", summary = stringResource(R.string.show_cache_folder_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.Cached,
                    title = showCacheFolderTitle,
                    subtitle = stringResource(R.string.show_cache_folder_subtitle),
                    trailing = {
                        Switch(
                            checked = showCacheFolder,
                            onCheckedChange = AppSettings::setShowCacheFolder,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowCacheFolder(!showCacheFolder) },
                )
            }
            val songCacheLimitTitle = stringResource(R.string.song_cache_limit)
            row(songCacheLimitTitle, "cache", "space", "unlimited", summary = stringResource(R.string.song_cache_limit_subtitle)) {
                SliderRow(
                    icon = Icons.Rounded.Storage,
                    title = songCacheLimitTitle,
                    subtitle = when {
                        cacheUnlimited -> stringResource(R.string.song_cache_unlimited_subtitle)
                        cacheLimitMb > CACHE_WARNING_MB ->
                            stringResource(R.string.song_cache_large_subtitle, cacheLimitLabel)
                        else -> stringResource(R.string.song_cache_limit_subtitle)
                    },
                    value = cacheLimitLabel,
                    sliderValue = cacheLimitMb.toFloat(),
                    onSliderValue = {
                        AppSettings.setAudioCacheLimitBytes(it.roundToInt().toLong() * 1024 * 1024)
                    },
                    valueRange = (AppSettings.DEFAULT_CACHE_LIMIT_BYTES / (1024 * 1024)).toFloat()..
                        CACHE_UNLIMITED_STOP_MB.toFloat(),
                    steps = 19,
                )
            }
        }
        // Last on the page, apart from the settings above: these throw things away.
        SearchableSettingsGroup(search, category = SettingsCategory.DOWNLOADS, header = SettingsText.GROUP_FREE_SPACE.text()) {
            val clearSongCacheTitle = stringResource(R.string.clear_song_cache)
            row(clearSongCacheTitle, "cache", "free space", summary = stringResource(R.string.clear_song_cache_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.DeleteSweep,
                    title = clearSongCacheTitle,
                    subtitle = stringResource(R.string.clear_song_cache_subtitle),
                    onClick = { confirmClearSongCache = true },
                )
            }
            val clearImageCacheTitle = stringResource(R.string.clear_image_cache)
            row(clearImageCacheTitle, "cache", "artwork", "free space", summary = stringResource(R.string.clear_image_cache_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.DeleteSweep,
                    title = clearImageCacheTitle,
                    subtitle = stringResource(R.string.clear_image_cache_subtitle),
                    onClick = {
                        val loader = SingletonImageLoader.get(context)
                        loader.memoryCache?.clear()
                        loader.diskCache?.clear()
                        Toast.makeText(context, context.getString(R.string.image_cache_cleared), Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }

        // ---- Lyrics & music video ----
        SearchableSettingsGroup(search, category = SettingsCategory.LYRICS, header = SettingsText.GROUP_LYRICS.text()) {
            val syncedLyricsTitle = stringResource(R.string.synced_lyrics)
            // Same reasoning as Animated cover art above: the lyrics source and
            // translation rows are only here while this is on.
            row(syncedLyricsTitle, "lyrics", "translation", "lrclib", "musixmatch", summary = stringResource(R.string.synced_lyrics_subtitle)) {
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.Notes,
                    title = syncedLyricsTitle,
                    subtitle = stringResource(R.string.synced_lyrics_subtitle),
                    trailing = {
                        Switch(
                            checked = syncedLyrics,
                            onCheckedChange = AppSettings::setSyncedLyrics,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setSyncedLyrics(!syncedLyrics) },
                )
            }
            // Nothing to choose between while the feature is off, and the
            // sources are third-party services being reached on the user's
            // connection — which is the part worth being able to narrow.
            if (syncedLyrics) {
                val lyricsBlurTitle = stringResource(R.string.blur_unfocused_lyrics)
                row(lyricsBlurTitle, "lyrics", "blur", summary = stringResource(R.string.blur_unfocused_lyrics_subtitle)) {
                    SettingsRow(
                        icon = Icons.Rounded.BlurOn,
                        title = lyricsBlurTitle,
                        subtitle = stringResource(R.string.blur_unfocused_lyrics_subtitle),
                        trailing = {
                            Switch(
                                checked = lyricsBlur,
                                onCheckedChange = AppSettings::setLyricsBlur,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setLyricsBlur(!lyricsBlur) },
                    )
                }
                val lyricsSourcesTitle = stringResource(R.string.lyrics_sources)
                row(lyricsSourcesTitle, "lyrics", "lrclib", "musixmatch") {
                    SettingsRow(
                        icon = Icons.Rounded.Language,
                        title = lyricsSourcesTitle,
                        subtitle = lyricsSources
                            .sortedBy { it.ordinal }
                            .joinToString(", ") { it.label }
                            .ifEmpty { stringResource(R.string.no_lyrics_sources_enabled) },
                        trailing = { Chevron() },
                        onClick = onLyricsSources,
                    )
                }
                val translationLanguageTitle = stringResource(R.string.translation_language)
                row(translationLanguageTitle, "lyrics", "translate", summary = stringResource(R.string.translation_language_subtitle)) {
                    SettingsRow(
                        icon = Icons.Rounded.Translate,
                        title = translationLanguageTitle,
                        subtitle = if (translationLanguage.isBlank()) {
                            stringResource(R.string.translation_language_subtitle)
                        } else {
                            translationLanguageName(
                                translationLanguage,
                                AppCompatDelegate.getApplicationLocales().get(0) ?: Locale.getDefault(),
                            )
                        },
                        trailing = { Chevron() },
                        onClick = onTranslationLanguage,
                    )
                }
            }
        }
        SearchableSettingsGroup(search, category = SettingsCategory.LYRICS, header = SettingsText.GROUP_CANVAS.text()) {
            val animatedCoverArtTitle = stringResource(R.string.animated_cover_art)
            // Carries the keywords of the rows that only appear once it is on,
            // so searching "spotify" with animated covers switched off turns up
            // the switch that brings the Spotify row back rather than nothing.
            row(animatedCoverArtTitle, "canvas", "video", "spotify", summary = stringResource(R.string.animated_cover_art_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.Animation,
                    title = animatedCoverArtTitle,
                    subtitle = stringResource(R.string.animated_cover_art_subtitle),
                    trailing = {
                        Switch(
                            checked = animatedCanvas,
                            onCheckedChange = AppSettings::setAnimatedCanvas,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setAnimatedCanvas(!animatedCanvas) },
                )
            }
            // Reads as part of the Animated cover art option above it, not
            // as a separate setting. Nothing to narrow while the clip itself
            // is off. Defaults to off: a clip loops for as long as its track
            // plays, so on cellular this is not a one-time video cost but
            // that cost repeated on every loop — see AppSettings.canvasOverCellular.
            if (animatedCanvas) {
                val coverCellularTitle = stringResource(R.string.animated_cover_cellular)
                row(coverCellularTitle, "canvas", "cellular", divided = false) {
                    SettingsSubRow(
                        title = coverCellularTitle,
                        checked = canvasOverCellular,
                        onCheckedChange = AppSettings::setCanvasOverCellular,
                    )
                }
                val spotifyCanvasTitle = stringResource(R.string.integrate_spotify_canvas)
                row(spotifyCanvasTitle, "spotify", "canvas", divided = false) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onSpotifyCanvasAuth)
                            .padding(start = ROW_INSET, end = ROW_INSET, top = 4.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = spotifyCanvasTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Chevron()
                    }
                }
                val openCanvasTitle = "Dynamic YouTube Canvas (OpenCanvas)"
                row(openCanvasTitle, "opencanvas", "canvas", "video", divided = false) {
                    SettingsSubRow(
                        title = openCanvasTitle,
                        subtitle = "Plays the artist's music video behind the song, in step with it. Powered by OpenCanvas. Looks up the video on YouTube and sync maps on jsDelivr.",
                        checked = openCanvasEnabled,
                        onCheckedChange = AppSettings::setOpenCanvasEnabled,
                    )
                }
                if (openCanvasEnabled) {
                    val openCanvasSyncTitle = "OpenCanvas Playback"
                    row(openCanvasSyncTitle, "opencanvas", "sync", "seek", "video", divided = false) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = ROW_INSET, end = ROW_INSET, top = 4.dp, bottom = 12.dp)
                        ) {
                            Text(
                                text = openCanvasSyncTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Synced to the song: the video starts at the song's position, jumps when you seek or skip, " +
                                    "pauses with the music and never loops. Tracks whose video can't be aligned keep the still cover.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    val openCanvasResTitle = "OpenCanvas Video Resolution"
                    val resOptions = listOf("360p", "480p", "720p", "1080p")
                    val selectedResIndex = resOptions.indexOf(openCanvasResolution).let { if (it >= 0) it else 1 }
                    row(openCanvasResTitle, "opencanvas", "resolution", "quality", "360p", "480p", "720p", "1080p", divided = false) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = ROW_INSET, end = ROW_INSET, top = 4.dp, bottom = 12.dp)
                        ) {
                            Text(
                                text = openCanvasResTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = when (openCanvasResolution) {
                                    "360p" -> "360p — Ultra-smooth playback & lowest battery/data usage"
                                    "720p" -> "720p — High Definition; sharp on large displays"
                                    "1080p" -> "1080p — Full HD clarity; highest quality"
                                    else -> "480p — Standard mobile canvas quality (Recommended)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            SegmentedControl(
                                options = resOptions,
                                selectedIndex = selectedResIndex,
                                onSelect = { AppSettings.setOpenCanvasResolution(resOptions[it]) },
                            )
                        }
                    }
                }
            }
        }
        SearchableSettingsGroup(search, category = SettingsCategory.LYRICS, header = SettingsText.GROUP_MUSIC_VIDEO.text()) {
            val preferMusicOnlyTitle = stringResource(R.string.prefer_music_only)
            row(preferMusicOnlyTitle, "video", "audio", "music video", summary = stringResource(R.string.prefer_music_only_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.SmartDisplay,
                    title = preferMusicOnlyTitle,
                    subtitle = stringResource(R.string.prefer_music_only_subtitle),
                    trailing = {
                        Switch(
                            checked = preferMusicOnly,
                            onCheckedChange = AppSettings::setPreferMusicOnly,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setPreferMusicOnly(!preferMusicOnly) },
                )
            }
            val smartAlignmentTitle = stringResource(R.string.smart_version_alignment)
            row(smartAlignmentTitle, "alignment", "sync", "waveform", "video", "audio", "skit", "intro", summary = stringResource(R.string.smart_version_alignment_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.GraphicEq,
                    title = smartAlignmentTitle,
                    subtitle = stringResource(R.string.smart_version_alignment_subtitle),
                    trailing = {
                        Switch(
                            checked = smartVersionAlignment,
                            onCheckedChange = AppSettings::setSmartVersionAlignment,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setSmartVersionAlignment(!smartVersionAlignment) },
                )
            }
        }

        // ---- Appearance ----
        SearchableSettingsGroup(search, category = SettingsCategory.APPEARANCE, header = stringResource(R.string.appearance)) {
            val themeTitle = stringResource(R.string.theme)
            row(themeTitle, "dark mode", "light mode") {
                SettingsRow(icon = Icons.Rounded.Brightness4, title = themeTitle)
                SegmentedControl(
                    options = ThemeMode.entries.map { it.localizedLabel() },
                    selectedIndex = ThemeMode.entries.indexOf(theme),
                    onSelect = { AppSettings.setThemeMode(ThemeMode.entries[it]) },
                    modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                )
            }
            row("new look", "colours follow the cover", "preview the look", "cosmotune", "colour", "color", "intensity") {
                com.cosmotune.ui.CosmoLookSettingsRows()
            }
        }
        SearchableSettingsGroup(search, category = SettingsCategory.APPEARANCE, header = SettingsText.GROUP_PLAYER.text()) {
            // Left out where the player won't honour it: a window too wide for
            // the player to fill and too narrow to stand a page beside it keeps
            // the sleeve either way. A docked pane is a phone's width, so it does
            // honour it — see [fullBleedArtworkAvailable].
            if (fullBleedArtworkAvailable(windowWidth)) {
                val fullScreenCoverArtTitle = stringResource(R.string.full_screen_cover_art)
                row(fullScreenCoverArtTitle, "artwork", "player", summary = stringResource(R.string.full_screen_cover_art_subtitle)) {
                    SettingsRow(
                        icon = Icons.Rounded.Fullscreen,
                        title = fullScreenCoverArtTitle,
                        subtitle = stringResource(R.string.full_screen_cover_art_subtitle),
                        trailing = {
                            Switch(
                                checked = fullBleedArtwork,
                                onCheckedChange = AppSettings::setFullBleedArtwork,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setFullBleedArtwork(!fullBleedArtwork) },
                    )
                }
            }
            val legacyMeshGradientTitle = stringResource(R.string.legacy_mesh_gradient)
            row(legacyMeshGradientTitle, "background", "player", summary = stringResource(R.string.legacy_mesh_gradient_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.Gradient,
                    title = legacyMeshGradientTitle,
                    subtitle = stringResource(R.string.legacy_mesh_gradient_subtitle),
                    trailing = {
                        Switch(
                            checked = legacyMeshGradient,
                            onCheckedChange = AppSettings::setLegacyMeshGradient,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setLegacyMeshGradient(!legacyMeshGradient) },
                )
            }
            row(stringResource(R.string.artist_signatures), "artist", "signature", summary = stringResource(R.string.artist_signatures_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.Animation,
                    title = stringResource(R.string.artist_signatures),
                    subtitle = stringResource(R.string.artist_signatures_subtitle),
                    trailing = { Switch(checked = signatureEnabled, onCheckedChange = AppSettings::setSignatureApiEnabled) },
                    onClick = { AppSettings.setSignatureApiEnabled(!signatureEnabled) },
                )
            }
            row(stringResource(R.string.artist_signatures_url), "artist", "signature", "url") {
                SettingsRow(
                    icon = Icons.Rounded.Animation,
                    title = stringResource(R.string.artist_signatures_url),
                    subtitle = if (signatureUrl.isBlank()) stringResource(R.string.artist_signatures_unconfigured) else stringResource(R.string.connected),
                    onClick = onArtistSignatureApi,
                )
            }
            val hideVolumeBarTitle = stringResource(R.string.hide_volume_bar)
            row(hideVolumeBarTitle, "volume", "player", summary = stringResource(R.string.hide_volume_bar_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.VolumeOff,
                    title = hideVolumeBarTitle,
                    subtitle = stringResource(R.string.hide_volume_bar_subtitle),
                    trailing = {
                        Switch(
                            checked = hideVolumeBar,
                            onCheckedChange = AppSettings::setHideVolumeBar,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setHideVolumeBar(!hideVolumeBar) },
                )
            }
            val queueHistoryTitle = stringResource(R.string.show_queue_history)
            row(queueHistoryTitle, "queue", "history", "player", summary = stringResource(R.string.show_queue_history_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.History,
                    title = queueHistoryTitle,
                    subtitle = stringResource(R.string.show_queue_history_subtitle),
                    trailing = { Switch(checked = queueHistory, onCheckedChange = AppSettings::setShowQueueHistory) },
                    onClick = { AppSettings.setShowQueueHistory(!queueHistory) },
                )
            }
            val hideStatusBarTitle = stringResource(R.string.hide_status_bar_in_player)
            row(hideStatusBarTitle, "status bar", "player", summary = stringResource(R.string.hide_status_bar_in_player_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.Fullscreen,
                    title = hideStatusBarTitle,
                    subtitle = stringResource(R.string.hide_status_bar_in_player_subtitle),
                    trailing = { Switch(checked = hideStatusBar, onCheckedChange = AppSettings::setHideStatusBarInPlayer) },
                    onClick = { AppSettings.setHideStatusBarInPlayer(!hideStatusBar) },
                )
            }
            val hideSongStatusTitle = stringResource(R.string.hide_song_status)
            row(hideSongStatusTitle, "player", "playing from", "played by", summary = stringResource(R.string.hide_song_status_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.VisibilityOff,
                    title = hideSongStatusTitle,
                    subtitle = stringResource(R.string.hide_song_status_subtitle),
                    trailing = {
                        Switch(
                            checked = hideSongStatus,
                            onCheckedChange = AppSettings::setHideSongStatus,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setHideSongStatus(!hideSongStatus) },
                )
            }
        }
        SearchableSettingsGroup(search, category = SettingsCategory.APPEARANCE, header = SettingsText.GROUP_MOTION.text()) {
            val reduceAnimationTitle = stringResource(R.string.reduce_animation)
            row(reduceAnimationTitle, "motion", summary = stringResource(R.string.reduce_animation_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.MotionPhotosOff,
                    title = reduceAnimationTitle,
                    subtitle = stringResource(R.string.reduce_animation_subtitle),
                    trailing = {
                        Switch(
                            checked = reduceAnimation,
                            onCheckedChange = AppSettings::setReduceAnimation,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setReduceAnimation(!reduceAnimation) },
                )
            }
            val reduceDynamicBlurTitle = stringResource(R.string.reduce_dynamic_blur)
            row(reduceDynamicBlurTitle, "blur", "performance", summary = stringResource(R.string.reduce_dynamic_blur_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.BlurOff,
                    title = reduceDynamicBlurTitle,
                    subtitle = stringResource(R.string.reduce_dynamic_blur_subtitle),
                    trailing = {
                        Switch(
                            checked = reduceDynamicBlur,
                            onCheckedChange = AppSettings::setReduceDynamicBlur,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setReduceDynamicBlur(!reduceDynamicBlur) },
                )
            }
            val liquidGlassTitle = stringResource(R.string.liquid_glass)
            row(liquidGlassTitle, "glass", "blur", summary = stringResource(R.string.liquid_glass_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = liquidGlassTitle,
                    subtitle = stringResource(
                        if (liquidGlassSupported) {
                            R.string.liquid_glass_subtitle
                        } else {
                            R.string.liquid_glass_unavailable
                        },
                    ),
                    enabled = liquidGlassSupported,
                    trailing = {
                        Switch(
                            checked = liquidGlass,
                            onCheckedChange = AppSettings::setLiquidGlass,
                            enabled = liquidGlassSupported,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setLiquidGlass(!liquidGlass) },
                )
            }
        }
        SearchableSettingsGroup(search, category = SettingsCategory.APPEARANCE, header = stringResource(R.string.performance)) {
            val highPerformanceModeTitle = stringResource(R.string.high_performance_mode)
            row(highPerformanceModeTitle, "frame rate", "refresh rate", "hz", "battery", "smooth", summary = stringResource(R.string.high_performance_subtitle)) {
                SettingsRow(
                    icon = CosmotuneIcons.Performance,
                    title = highPerformanceModeTitle,
                    subtitle = if (highPerformanceMode) {
                        stringResource(R.string.high_performance_active, selectedPerformanceRefreshRate)
                    } else {
                        stringResource(R.string.high_performance_subtitle)
                    },
                    trailing = {
                        Switch(
                            checked = highPerformanceMode,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    showPerformanceWarning = true
                                } else {
                                    AppSettings.setHighPerformanceMode(false)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = {
                        if (highPerformanceMode) {
                            AppSettings.setHighPerformanceMode(false)
                        } else {
                            showPerformanceWarning = true
                        }
                    },
                )
            }
            if (highPerformanceMode) {
                val refreshRateTitle = stringResource(R.string.refresh_rate)
                row(refreshRateTitle, "hz", "frame rate") {
                    SettingsRow(
                        icon = CosmotuneIcons.FrameRate,
                        title = refreshRateTitle,
                    )
                    SegmentedControl(
                        options = supportedRefreshRates.map { "$it Hz" },
                        selectedIndex = supportedRefreshRates.indexOf(selectedPerformanceRefreshRate),
                        onSelect = { index ->
                            AppSettings.setPerformanceRefreshRate(supportedRefreshRates[index])
                        },
                        modifier = Modifier.padding(
                            start = TEXT_INSET,
                            end = ROW_INSET,
                            bottom = 14.dp,
                        ),
                    )
                }
            }
        }
        SearchableSettingsGroup(search, category = SettingsCategory.APPEARANCE, header = stringResource(R.string.language)) {
            val appLanguageTitle = stringResource(R.string.app_language)
            row(appLanguageTitle, "locale", "translate") {
                val selectedLanguage = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
                    ?: Locale.getDefault().toLanguageTag()
                SettingsRow(
                    icon = Icons.Rounded.Language,
                    title = appLanguageTitle,
                    subtitle = stringResource(languageDisplayNameRes(selectedLanguage)),
                    onClick = onAppLanguage,
                )
            }
        }

        // ---- Video, Manga & Read, Podio: the modes keep their own settings; their pages here lead to them ----
        SearchableSettingsGroup(search, category = SettingsCategory.VIDEO) {
            val openVideoTitle = SettingsText.OPEN_VIDEO.text()
            val openVideoSummary = SettingsText.OPEN_VIDEO_SUMMARY.text()
            row(openVideoTitle, "youtube", "sponsorblock", "captions", "subtitles", "dearrow", "quality", summary = openVideoSummary) {
                ModeLinkRow(AppMode.VIDEO, Icons.Rounded.SmartDisplay, openVideoTitle, openVideoSummary)
            }
        }
        SearchableSettingsGroup(search, category = SettingsCategory.READ) {
            val openReadTitle = SettingsText.OPEN_READ.text()
            val openReadSummary = SettingsText.OPEN_READ_SUMMARY.text()
            row(openReadTitle, "manga", "books", "reader", "komga", "epub", summary = openReadSummary) {
                ModeLinkRow(AppMode.MANGA, Icons.AutoMirrored.Rounded.MenuBook, openReadTitle, openReadSummary)
            }
        }
        SearchableSettingsGroup(search, category = SettingsCategory.PODIO) {
            val openPodioTitle = SettingsText.OPEN_PODIO.text()
            val openPodioSummary = SettingsText.OPEN_PODIO_SUMMARY.text()
            row(openPodioTitle, "podcasts", "radio", "episodes", summary = openPodioSummary) {
                ModeLinkRow(AppMode.PODIO, Icons.Rounded.Podcasts, openPodioTitle, openPodioSummary)
            }
        }

        // ---- Listening tools & recommendations ----
        SearchableSettingsGroup(search, category = SettingsCategory.LISTENING) {
            row("Learn from my listening", "recommendations", "learning", "personalization") {
                com.cosmotune.ui.listening.RecommendationLearningSettings()
            }
            val dontRepeatSongsTitle = stringResource(R.string.dont_repeat_songs)
            row(dontRepeatSongsTitle, "radio", "suggestions", summary = stringResource(R.string.dont_repeat_songs_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.History,
                    title = dontRepeatSongsTitle,
                    subtitle = stringResource(R.string.dont_repeat_songs_subtitle),
                    trailing = {
                        Switch(
                            checked = dontRepeatSuggestions,
                            onCheckedChange = AppSettings::setDontRepeatSuggestions,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setDontRepeatSuggestions(!dontRepeatSuggestions) },
                )
            }
            val replayTitle = stringResource(R.string.replay)
            row(replayTitle, "stats", "history", "wrapped", summary = stringResource(R.string.replay_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.BarChart,
                    title = replayTitle,
                    subtitle = stringResource(R.string.replay_subtitle),
                    onClick = onOpenReplay,
                )
            }
            val workOutGenresTitle = stringResource(R.string.work_out_genres)
            row(workOutGenresTitle, "genre", "replay") {
                SettingsRow(
                    icon = Icons.Rounded.LocalOffer,
                    title = workOutGenresTitle,
                    subtitle = if (replayGenres) {
                        stringResource(R.string.replay_genres_enabled_subtitle)
                    } else {
                        stringResource(R.string.replay_genres_disabled_subtitle)
                    },
                    trailing = {
                        Switch(
                            checked = replayGenres,
                            onCheckedChange = AppSettings::setReplayGenres,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setReplayGenres(!replayGenres) },
                )
            }
        }

        // ---- Integrations ----
        SearchableSettingsGroup(search, category = SettingsCategory.INTEGRATIONS) {
            // Sits with the account rather than with Playback: a party is up to
            // five signed-in people, and being signed in is the whole of what
            // the row needs before it will do anything.
            val listenTogetherTitle = stringResource(R.string.listen_together)
            row(listenTogetherTitle, "jam", "party", "sync", "friends", summary = stringResource(R.string.listen_together_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.Groups,
                    title = listenTogetherTitle,
                    subtitle = party.code?.let {
                        stringResource(R.string.listen_together_in_party, it)
                    } ?: stringResource(R.string.listen_together_subtitle),
                    badge = party.members.size.takeIf { party.inParty && it > 1 }?.toString(),
                    onClick = onListenTogether,
                )
            }
            row(stringResource(R.string.wear_os_section), "watch", "wear os", "galaxy watch", "pixel watch", "install") {
                WearOsSettingsRow(onClick = onWearOs)
            }
            // A second way to the account screen: Discord, Last.fm and ListenBrainz live behind it.
            val servicesTitle = SettingsText.SERVICES.text()
            val servicesSummary = SettingsText.SERVICES_SUMMARY.text()
            row(servicesTitle, "scrobbling", "last.fm", "listenbrainz", "discord", "spotify", summary = servicesSummary) {
                SettingsRow(
                    icon = Icons.Rounded.Share,
                    title = servicesTitle,
                    subtitle = servicesSummary,
                    onClick = onAccountScrobbling,
                )
            }
        }

        // ---- Privacy & backup ----
        SearchableSettingsGroup(search, category = SettingsCategory.PRIVACY, header = stringResource(R.string.your_data)) {
            val exportDataTitle = stringResource(R.string.export_data)
            row(exportDataTitle, "backup", summary = stringResource(R.string.export_data_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.FileUpload,
                    title = exportDataTitle,
                    subtitle = exportStatus ?: stringResource(R.string.export_data_subtitle),
                    onClick = { exportPicker.launch(Backup.suggestedName()) },
                )
            }
            val importDataTitle = stringResource(R.string.import_data)
            row(importDataTitle, "backup", "restore", summary = stringResource(R.string.import_data_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.FileDownload,
                    title = importDataTitle,
                    subtitle = importStatus ?: stringResource(R.string.import_data_subtitle),
                    onClick = { confirmImport = true },
                )
            }
        }

        // ---- About & updates ----
        SearchableSettingsGroup(search, category = SettingsCategory.ABOUT, header = SettingsText.GROUP_ABOUT.text()) {
            val uriHandler = LocalUriHandler.current
            val versionTitle = SettingsText.VERSION.text()
            row(versionTitle, "cosmotune", "build", "update", "updates", summary = version) {
                SettingsRow(
                    icon = Icons.Rounded.Info,
                    title = versionTitle,
                    trailing = {
                        Text(
                            text = version,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
            val sourceCodeTitle = SettingsText.SOURCE_CODE.text()
            row(sourceCodeTitle, "github", "open source", "licence", "license", summary = com.cosmotune.CosmoConfig.PROJECT_URL) {
                SettingsRow(
                    icon = Icons.Rounded.Code,
                    title = sourceCodeTitle,
                    subtitle = com.cosmotune.CosmoConfig.PROJECT_URL,
                    onClick = { uriHandler.openUri(com.cosmotune.CosmoConfig.PROJECT_URL) },
                )
            }
            val websiteTitle = SettingsText.WEBSITE.text()
            row(websiteTitle, "site", "homepage", summary = com.cosmotune.CosmoConfig.SITE_URL) {
                SettingsRow(
                    icon = Icons.Rounded.Public,
                    title = websiteTitle,
                    subtitle = com.cosmotune.CosmoConfig.SITE_URL,
                    onClick = { uriHandler.openUri(com.cosmotune.CosmoConfig.SITE_URL) },
                )
            }
        }
        SearchableSettingsGroup(search, category = SettingsCategory.ABOUT, header = stringResource(R.string.advanced_options)) {
            val showNerdStatsTitle = stringResource(R.string.show_nerd_stats)
            row(showNerdStatsTitle, "debug", "bitrate", "codec", summary = stringResource(R.string.show_nerd_stats_subtitle)) {
                SettingsRow(
                    icon = Icons.Rounded.BarChart,
                    title = showNerdStatsTitle,
                    subtitle = stringResource(R.string.show_nerd_stats_subtitle),
                    trailing = {
                        Switch(
                            checked = nerdStats,
                            onCheckedChange = AppSettings::setShowNerdStats,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowNerdStats(!nerdStats) },
                )
            }
        }

        }

        // The page's colophon, under About.
        if (collector == null && selectedCategory == SettingsCategory.ABOUT) {
        // Watch mode's films and series come from TMDB, whose terms ask for this line and its logo in the credits.
        com.cosmotune.ui.settings.TmdbAttribution(Modifier.padding(top = 24.dp))
        Text(
            text = buildAnnotatedString {
                append("Made with love by Vijay\n")
                append("Cosmotune $version  ")
                val linkStyles = TextLinkStyles(
                    style = SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    ),
                )
                withLink(LinkAnnotation.Url(com.cosmotune.CosmoConfig.PROJECT_URL, linkStyles)) {
                    append("Source")
                }
                append("  ")
                withLink(LinkAnnotation.Url(com.cosmotune.CosmoConfig.SITE_URL, linkStyles)) {
                    append("Website")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
        )
        }

        // Read after every page has registered its rows, which is what makes the list complete.
        if (collector != null) {
            val results = SettingsIndex.search(hubEntries.asSearchItems() + collector.items, searchQuery)
            SettingsSearchResults(
                results = results,
                query = searchQuery,
                emptyMessage = stringResource(R.string.settings_search_empty, searchQuery),
                onPick = { item ->
                    searchQuery = ""
                    selectedCategory = item.category
                    if (!item.id.startsWith(CATEGORY_ID_PREFIX)) highlight.flash(item.id)
                },
            )
        }
    }

    picking?.let { target ->
        // An Orbit sheet with Orbit rows (the song sheet's surface; the chosen rung solid ink with the peach dot).
        com.cosmotune.ui.orbit.OrbitPickerSheet(onDismiss = { picking = null }) {
            QualitySheet(
                target = target,
                selected = when (target) {
                    QualityTarget.WIFI -> wifiQuality
                    QualityTarget.CELLULAR -> cellularQuality
                },
                // Writes the one ceiling that was being edited and nothing
                // else. There used to be a `SourceRegistry.applyQualityPreset`
                // call here that flipped the module and JioSaavn switches to
                // match — which meant budgeting *mobile data* switched those
                // sources off while sitting on Wi-Fi, and coming back to Wi-Fi
                // never switched them on again. Which sources a rung consults
                // is now read per stream off the connection in force; see
                // [AudioQuality.permits].
                onSelect = { quality ->
                    when (target) {
                        QualityTarget.WIFI -> AppSettings.setAudioQualityWifi(quality)
                        QualityTarget.CELLULAR -> AppSettings.setAudioQualityCellular(quality)
                    }
                    picking = null
                },
            )
        }
    }

    if (pickingDownloadQuality) {
        // An Orbit sheet with Orbit rows (the song sheet's surface; the chosen rung solid ink with the peach dot).
        com.cosmotune.ui.orbit.OrbitPickerSheet(onDismiss = { pickingDownloadQuality = false }) {
            DownloadQualitySheet(
                selected = downloadQuality,
                onSelect = { quality ->
                    AppSettings.setDownloadQuality(quality)
                    pickingDownloadQuality = false
                },
            )
        }
    }

    if (pickingAutomixPerformance) {
        // An Orbit sheet with Orbit rows (the song sheet's surface; the chosen rung solid ink with the peach dot).
        com.cosmotune.ui.orbit.OrbitPickerSheet(onDismiss = { pickingAutomixPerformance = false }) {
            AutomixPerformanceSheet(
                selected = automixPerformance,
                onSelect = { mode ->
                    AppSettings.setAutomixPerformanceMode(mode)
                    pickingAutomixPerformance = false
                },
            )
        }
    }

    // Everything in the Cached songs folder goes with it, so say so first.
    if (confirmClearSongCache) {
        com.cosmotune.ui.sheets.CosmoAlertDialog(
            onDismissRequest = { confirmClearSongCache = false },
            title = stringResource(R.string.clear_song_cache_title),
            message = stringResource(R.string.clear_song_cache_warning),
            confirmLabel = stringResource(R.string.clear),
            onConfirm = {
                    confirmClearSongCache = false
                    AudioCache.clear {
                        Toast.makeText(context, context.getString(R.string.song_cache_cleared), Toast.LENGTH_SHORT).show()
                    }
                },
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { confirmClearSongCache = false },
        )
    }

    // Says what stays as well as what goes: the playlists on this phone are untouched, which is the first thing anyone wonders.
    if (confirmCloudDelete) {
        com.cosmotune.ui.sheets.CosmoAlertDialog(
            onDismissRequest = { confirmCloudDelete = false },
            title = stringResource(R.string.cloud_delete),
            message = stringResource(R.string.cloud_delete_warning),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                    confirmCloudDelete = false
                    onCloudDeleteAccount()
                },
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { confirmCloudDelete = false },
        )
    }

    // Asked before the picker opens rather than after a file is chosen: the
    // thing being confirmed is that this device's own history is about to be
    // thrown away, and that is true whichever file gets picked.
    if (confirmImport) {
        com.cosmotune.ui.sheets.CosmoAlertDialog(
            onDismissRequest = { confirmImport = false },
            title = stringResource(R.string.import_backup_title),
            message = stringResource(R.string.import_backup_warning),
            confirmLabel = stringResource(R.string.choose_file),
            onConfirm = {
                    confirmImport = false
                    importPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                },
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { confirmImport = false },
        )
    }

    if (showPerformanceWarning) {
        com.cosmotune.ui.sheets.CosmoAlertDialog(
            onDismissRequest = { showPerformanceWarning = false },
            title = stringResource(R.string.high_performance_before_enabling),
            message = stringResource(R.string.high_performance_battery_warning),
            confirmLabel = stringResource(R.string.open_battery_settings),
            onConfirm = {
                        showPerformanceWarning = false
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        val settingsIntent = intent.takeIf {
                            it.resolveActivity(context.packageManager) != null
                        } ?: Intent(Settings.ACTION_SETTINGS)
                        batterySettingsLauncher.launch(settingsIntent)
                    },
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { showPerformanceWarning = false },
        )
    }

    if (showPerformanceConfirmation) {
        com.cosmotune.ui.sheets.CosmoAlertDialog(
            onDismissRequest = { showPerformanceConfirmation = false },
            title = stringResource(R.string.enable_high_performance_title),
            message = stringResource(R.string.enable_high_performance_confirmation),
            confirmLabel = stringResource(R.string.enable),
            onConfirm = {
                        showPerformanceConfirmation = false
                        AppSettings.setHighPerformanceMode(true)
                    },
            dismissLabel = stringResource(R.string.not_yet),
            onDismiss = { showPerformanceConfirmation = false },
        )
    }

    if (showListenBrainzTokenDialog) {
        var tokenInput by remember { mutableStateOf(listenBrainzToken) }
        com.cosmotune.ui.sheets.CosmoAlertDialog(
            onDismissRequest = { showListenBrainzTokenDialog = false },
            title = stringResource(R.string.listenbrainz_token),
            content = {
                com.cosmotune.ui.orbit.OrbitTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    placeholder = stringResource(R.string.api_token),
                    fonts = com.cosmotune.ui.orbit.orbitFonts(),
                    look = com.cosmotune.ui.orbit.orbitLook(),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmLabel = stringResource(R.string.save),
            onConfirm = {
                    AppSettings.setListenBrainzToken(tokenInput.trim())
                    showListenBrainzTokenDialog = false
                },
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { showListenBrainzTokenDialog = false },
        )
    }

    if (showLastfmLoginDialog) {
        var usernameInput by remember { mutableStateOf("") }
        var passwordInput by remember { mutableStateOf("") }
        var lastfmError by remember { mutableStateOf<String?>(null) }
        var lastfmLoading by remember { mutableStateOf(false) }
        com.cosmotune.ui.sheets.CosmoAlertDialog(
            onDismissRequest = { if (!lastfmLoading) showLastfmLoginDialog = false },
            title = stringResource(R.string.lastfm_login),
            content = {
                Column {
                    if (lastfmError != null) {
                        Text(
                            text = lastfmError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    com.cosmotune.ui.orbit.OrbitTextField(
                        value = usernameInput,
                        onValueChange = { usernameInput = it },
                        placeholder = stringResource(R.string.username),
                        fonts = com.cosmotune.ui.orbit.orbitFonts(),
                        look = com.cosmotune.ui.orbit.orbitLook(),
                        modifier = Modifier.fillMaxWidth(),
                        error = lastfmError != null,
                    )
                    Spacer(Modifier.height(8.dp))
                    com.cosmotune.ui.orbit.OrbitTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        placeholder = stringResource(R.string.password),
                        fonts = com.cosmotune.ui.orbit.orbitFonts(),
                        look = com.cosmotune.ui.orbit.orbitLook(),
                        modifier = Modifier.fillMaxWidth(),
                        error = lastfmError != null,
                        secret = true,
                    )
                }
            },
            confirmLabel = stringResource(if (lastfmLoading) R.string.signing_in else R.string.sign_in),
            onConfirm = {
                        lastfmLoading = true
                        lastfmError = null
                        scrobbleScope.launch {
                            try {
                                // Use the credentials supplied for this build.
                                LastFM.initialize(
                                    apiKey = AppSettings.lastfmApiKey.value,
                                    secret = AppSettings.lastfmSecret.value,
                                )
                                LastFM.getMobileSession(usernameInput.trim(), passwordInput)
                                    .onSuccess { auth ->
                                        AppSettings.setLastfmSessionKey(auth.session.key)
                                        AppSettings.setLastfmUsername(auth.session.name)
                                        AppSettings.setLastfmEnabled(true)
                                        showLastfmLoginDialog = false
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
            confirmEnabled = !lastfmLoading && usernameInput.isNotBlank() && passwordInput.isNotBlank(),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { showLastfmLoginDialog = false },
            dismissEnabled = !lastfmLoading,
        )
    }

}

/** "3 months of listening" — the unit a backup is actually measured in. */
private fun Context.countOfMonths(months: Int): String = if (months == 0) {
    getString(R.string.no_listening_history)
} else {
    resources.getQuantityString(R.plurals.listening_month_count, months, months)
}

/** Which ceiling the open picker is editing. */
private enum class QualityTarget(val icon: ImageVector) {
    WIFI(Icons.Rounded.Wifi),
    CELLULAR(Icons.Rounded.SignalCellularAlt),
}

@Composable
private fun QualityTarget.localizedTitle(): String = stringResource(
    if (this == QualityTarget.WIFI) R.string.wifi else R.string.mobile_data,
)

@Composable
private fun AudioQuality.localizedLabel(): String = stringResource(
    when (this) {
        AudioQuality.LOW -> R.string.low
        AudioQuality.MEDIUM -> R.string.medium
        AudioQuality.HIGH -> R.string.high
        AudioQuality.LOSSLESS -> R.string.lossless
    },
)

@Composable
private fun DownloadQuality.localizedLabel(): String = stringResource(
    when (this) {
        DownloadQuality.STANDARD -> R.string.standard
        DownloadQuality.HIGH -> R.string.high
        DownloadQuality.LOSSLESS -> R.string.lossless
    },
)

@Composable
private fun ThemeMode.localizedLabel(): String = stringResource(
    when (this) {
        ThemeMode.SYSTEM -> R.string.system
        ThemeMode.LIGHT -> R.string.light
        ThemeMode.DARK -> R.string.dark
    },
)

@Composable
private fun AutomixPerformanceMode.localizedLabel(): String = stringResource(
    when (this) {
        AutomixPerformanceMode.EFFICIENT -> R.string.automix_mode_efficient
        AutomixPerformanceMode.BALANCED -> R.string.automix_mode_balanced
        AutomixPerformanceMode.PERFORMANCE -> R.string.automix_mode_performance
    },
)

/** Who the cloud account is and how its last sync went, as one line: "Signed in as Ann · Last synced 5 minutes ago". */
@Composable
private fun cloudStatusLine(status: com.cosmotune.cloud.CloudStatus): String {
    val who = listOfNotNull(status.name, status.email).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    val identity = if (status.signedIn && !status.isGuest && who.isNotEmpty()) {
        stringResource(R.string.cloud_sync_signed_in_as, who)
    } else {
        stringResource(R.string.cloud_sync_guest)
    }
    val state = when {
        status.syncing -> stringResource(R.string.cloud_sync_syncing)
        status.needsSignIn -> stringResource(R.string.cloud_sync_sign_in_again)
        // Firebase's own words can run long; the row is a summary, the log has the rest.
        status.lastError != null -> stringResource(R.string.cloud_sync_failed, status.lastError.orEmpty().take(80))
        status.lastSyncMs > 0 -> stringResource(
            R.string.cloud_sync_last_synced,
            android.text.format.DateUtils.getRelativeTimeSpanString(
                status.lastSyncMs, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
            ).toString(),
        )
        else -> stringResource(R.string.cloud_sync_not_yet)
    }
    return "$identity · $state"
}

/**
 * Hands the device's own effect panel this app's audio session.
 *
 * Shared with [EqualizerScreen], which offers the same escape hatch a second
 * time next to the equaliser it might be an alternative to.
 */
internal fun openEqualizer(context: Context, sessionId: Int) {
    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, context.getString(R.string.no_equalizer), Toast.LENGTH_SHORT).show()
    }
}

/** Above this, the cache limit slider's subtitle warns rather than reassures. */
private const val CACHE_WARNING_MB = 2048

/**
 * Where the cache limit slider's "Unlimited" stop sits: one 512MB step past
 * [AppSettings.MAX_CACHE_LIMIT_BYTES], so the fixed sizes keep their positions.
 */
private const val CACHE_UNLIMITED_STOP_MB = 10 * 1024 + 512

/** "512 MB", "2 GB", "2.5 GB" — whichever reads more naturally at that size. */
private fun formatCacheSize(mb: Int): String {
    if (mb < 1024) return "$mb MB"
    val gb = mb / 1024f
    return if (gb == gb.toInt().toFloat()) "${gb.toInt()} GB" else "%.1f GB".format(Locale.ROOT, gb)
}

/** Who you're signed in as, straight from YouTube Music's account menu. */
@Composable
internal fun AccountCard(
    signedIn: Boolean,
    account: Account?,
    onSignIn: () -> Unit,
    onClick: (() -> Unit)? = null,
    /** Sits inside a [SettingsGroup] that already supplies the card, so draws none of its own. */
    grouped: Boolean = false,
    /** Where the library sync stands, under the account on the settings home (Orbit's account card). */
    syncLine: String? = null,
) {
    if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) return com.cosmotune.ui.settings.CosmoProfileCard(
        name = account?.name ?: stringResource(if (signedIn) R.string.signed_in else R.string.not_signed_in),
        subtitle = listOfNotNull(
            account?.email?.takeIf { it.isNotBlank() }
                ?: stringResource(if (signedIn) R.string.youtube_music_account else R.string.tap_to_sign_in_google),
            syncLine?.takeIf { signedIn },
        ).joinToString(" · "),
        label = if (signedIn) "Account · Google" else "Account",
        avatarUrl = account?.thumbnailUrl,
        onClick = when {
            signedIn && onClick != null -> onClick
            !signedIn -> onSignIn
            else -> null
        },
        grouped = grouped,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (grouped) {
                    Modifier
                } else {
                    Modifier
                        .padding(horizontal = GROUP_INSET)
                        .clip(GroupShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                },
            )
            .then(
                when {
                    signedIn && onClick != null -> Modifier.clickable(onClick = onClick)
                    !signedIn -> Modifier.clickable(onClick = onSignIn)
                    else -> Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (account?.thumbnailUrl != null) {
            AsyncImage(
                model = account.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(CircleShape).thumbnailBorder(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = account?.name
                    ?: stringResource(if (signedIn) R.string.signed_in else R.string.not_signed_in),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = account?.email?.takeIf { it.isNotBlank() }
                    ?: stringResource(
                        if (signedIn) R.string.youtube_music_account else R.string.tap_to_sign_in_google,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!signedIn) {
            Spacer(Modifier.width(8.dp))
            Chevron()
        }
    }
}

/** The quality options for one connection, with what each costs in data: an Orbit picker's title and rows, best first. */
@Composable
private fun QualitySheet(
    target: QualityTarget,
    selected: AudioQuality,
    onSelect: (AudioQuality) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    com.cosmotune.ui.orbit.OrbitSheetTitle(
        stringResource(R.string.audio_quality),
        label = target.localizedTitle(),
        subtitle = stringResource(R.string.audio_quality_connection, target.localizedTitle().lowercase(Locale.getDefault())),
    )
    PickerRows {
        // Best first — the option most people want shouldn't be last.
        AudioQuality.entries.reversed().forEach { quality ->
            com.cosmotune.ui.orbit.OrbitPickRow(
                title = quality.localizedLabel(),
                detail = stringResource(R.string.quality_hourly, quality.detail, quality.hourly),
                selected = quality == selected,
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(quality) },
            )
        }
    }
}

/** CPU budget picker for the background models that prepare Automix. */
@Composable
private fun AutomixPerformanceSheet(
    selected: AutomixPerformanceMode,
    onSelect: (AutomixPerformanceMode) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    com.cosmotune.ui.orbit.OrbitSheetTitle(
        stringResource(R.string.automix_performance),
        label = "Automix",
        subtitle = stringResource(R.string.automix_performance_warning),
    )
    PickerRows {
        AutomixPerformanceMode.entries.forEach { mode ->
            com.cosmotune.ui.orbit.OrbitPickRow(
                title = mode.localizedLabel(),
                detail = stringResource(
                    when (mode) {
                        AutomixPerformanceMode.EFFICIENT -> R.string.automix_mode_efficient_subtitle
                        AutomixPerformanceMode.BALANCED -> R.string.automix_mode_balanced_subtitle
                        AutomixPerformanceMode.PERFORMANCE -> R.string.automix_mode_performance_subtitle
                    },
                ),
                selected = mode == selected,
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(mode) },
            )
        }
    }
}

/**
 * What to keep when a track is saved, with what each rung costs on disk.
 *
 * Priced per track rather than per hour, the way [QualitySheet] is. That sheet
 * is answering "what will listening cost me this hour", because a stream is
 * spent again on every replay; this one is answering "what will keeping this
 * cost me", and the answer is charged once. Same widget, different question, so
 * the numbers beside the options are in different units on purpose.
 */
@Composable
private fun DownloadQualitySheet(
    selected: DownloadQuality,
    onSelect: (DownloadQuality) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    com.cosmotune.ui.orbit.OrbitSheetTitle(
        stringResource(R.string.download_quality),
        label = "Downloads",
        subtitle = stringResource(R.string.download_quality_dialog_subtitle),
    )
    PickerRows {
        // Best first, matching [QualitySheet] — and here the best rung is also
        // the default, so the mark starts where the eye does.
        DownloadQuality.entries.reversed().forEach { quality ->
            com.cosmotune.ui.orbit.OrbitPickRow(
                title = quality.localizedLabel(),
                detail = stringResource(R.string.quality_per_track, quality.detail, quality.perTrack),
                selected = quality == selected,
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(quality) },
            )
        }
    }
}

/** The rows of a picker sheet: a radio group, 6 dp apart, under its title. */
@Composable
private fun PickerRows(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

// ---- Building blocks --------------------------------------------------------

internal val GroupShape = RoundedCornerShape(CosmoRadii.rMd)
internal val GROUP_INSET = 16.dp
internal val ROW_INSET = 16.dp
internal val ICON_SIZE = 22.dp
internal val ICON_GAP = 14.dp

/** Where a row's text starts — dividers are inset to match, as on iOS. */
internal val TEXT_INSET = ROW_INSET + ICON_SIZE + ICON_GAP

/** The id of the provider packages entry, which stands outside every card. */
private const val PROVIDER_PACKAGES_ID = "SOURCES:provider_packages"

/**
 * What the groups of one pass are drawn for: the page of [category] (nothing for the settings home), or, while the search is
 * open, the [collector] every row registers with instead of drawing. [highlight] is the row a search result pointed at.
 */
private class SettingsSearch(
    val category: SettingsCategory,
    val collector: SettingsCollector?,
    val highlight: SettingsHighlight,
)

/** Collects the rows of one group, or registers them with the search. */
private class SettingsGroupScope(
    private val search: SettingsSearch,
    private val header: String?,
    private val category: SettingsCategory,
) {
    class Entry(val id: String, val divided: Boolean, val content: @Composable () -> Unit)

    val entries = mutableListOf<Entry>()

    /**
     * One searchable setting. [keywords] is everything somebody might type
     * looking for it: its title first, plus whatever its subtitle or the
     * controls under it say that the title doesn't — "Spotify", say, for the
     * canvas link that lives under Animated cover art. [summary] is what its
     * row says under the title, searched too and shown with the result.
     *
     * [divided] is false for the rows drawn as part of the row above them
     * rather than as settings in their own right, so they keep hugging their
     * parent.
     */
    fun row(
        vararg keywords: String?,
        divided: Boolean = true,
        summary: String? = null,
        content: @Composable () -> Unit,
    ) {
        val title = keywords.firstOrNull { !it.isNullOrBlank() } ?: return
        val id = "${category.name}:$title"
        val collector = search.collector
        if (collector != null) {
            // The group's header counts for every row beneath it: searching
            // "playback" turns up the playback rows, not nothing at all.
            collector.add(
                SettingsItem(
                    id = id,
                    title = title,
                    category = category,
                    summary = summary,
                    keywords = keywords.drop(1).filterNotNull(),
                    group = header,
                ),
            )
            return
        }
        entries += Entry(id, divided, content)
    }
}

/**
 * A [SettingsGroup] of one page: drawn only on its [category]'s page, and,
 * while the search is open, only registering its rows. Each row stands in a
 * column that the search's highlight can find and light up.
 */
@Composable
private fun SearchableSettingsGroup(
    search: SettingsSearch,
    header: String? = null,
    footer: String? = null,
    topSpacing: Dp = 26.dp,
    category: SettingsCategory,
    content: @Composable SettingsGroupScope.() -> Unit,
) {
    if (search.collector == null && search.category != category) return
    val scope = SettingsGroupScope(search, header, category)
    scope.content()
    if (scope.entries.isEmpty()) return
    SettingsGroup(header = header, footer = footer, topSpacing = topSpacing) {
        scope.entries.forEachIndexed { index, entry ->
            when {
                index > 0 && entry.divided -> RowDivider()
                // An undivided row is drawn tight to the top because something
                // it belongs to is usually above it. First in its card it has
                // nothing to sit under, so it is given that room back.
                index == 0 && !entry.divided -> Spacer(Modifier.height(10.dp))
            }
            Column(Modifier.fillMaxWidth().settingsHighlight(entry.id, search.highlight)) {
                entry.content()
            }
        }
    }
}

/**
 * The way from Music's settings into another mode, whose own settings live in
 * it. Only while the new look is on (the modes need it) and the mode works.
 */
@Composable
private fun ModeLinkRow(mode: AppMode, icon: ImageVector, title: String, summary: String) {
    val shell = com.cosmotune.ui.theme.LocalCosmoShell.current
    val available = shell != null && AppModes.available(mode)
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = if (available) summary else SettingsText.MODE_NEEDS_LOOK.text(),
        enabled = available,
        onClick = { shell?.settings?.setMode(mode) },
    )
}

/**
 * One inset card of rows, with an uppercase header above and an optional
 * plain-language [footer] below. Rows are separated by [RowDivider].
 */
@Composable
internal fun SettingsGroup(
    header: String? = null,
    footer: String? = null,
    /** Room above the card, where there is no [header] to provide it. */
    topSpacing: Dp = 26.dp,
    content: @Composable () -> Unit,
) {
    if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) return com.cosmotune.ui.settings.CosmoSettingsGroup(header = header, footer = footer, topSpacing = topSpacing, content = content)
    if (header != null) {
        Text(
            text = header.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = GROUP_INSET + 4.dp,
                end = GROUP_INSET,
                top = 26.dp,
                bottom = 8.dp,
            ),
        )
    } else {
        Spacer(Modifier.height(topSpacing))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET)
            .clip(GroupShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        content()
    }
    if (footer != null) {
        Text(
            text = footer,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = GROUP_INSET + 4.dp,
                end = GROUP_INSET + 4.dp,
                top = 8.dp,
            ),
        )
    }
}

@Composable
internal fun RowDivider() {
    if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) return com.cosmotune.ui.settings.CosmoRowDivider()
    HorizontalDivider(
        modifier = Modifier.padding(start = TEXT_INSET),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * The standard row: glyph, title, optional subtitle, and on the right either
 * [trailing] (a switch, say) or the current [value] followed by a chevron.
 *
 * [iconPainter] is for the handful of rows whose glyph is a drawable rather
 * than a Material icon — the Dolby double-D, which is a mark and not something
 * to approximate with the nearest speaker outline. Exactly one of it and [icon]
 * is expected; the painter wins where both are given.
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    value: String? = null,
    badge: String? = null,
    enabled: Boolean = true,
    iconPainter: Painter? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) return com.cosmotune.ui.settings.CosmoSettingsRow(icon = icon, title = title, subtitle = subtitle, subtitleContent = subtitleContent, value = value, badge = badge, enabled = enabled, iconPainter = iconPainter, onClick = onClick, trailing = trailing)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = 52.dp)
            .padding(horizontal = ROW_INSET, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconPainter != null) {
            Icon(
                painter = iconPainter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
        }
        Spacer(Modifier.width(ICON_GAP))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Badge(badge)
                }
            }
            if (subtitleContent != null) {
                subtitleContent()
            } else if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (trailing != null) {
            trailing()
        } else if (value != null || onClick != null) {
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
            }
            Chevron()
        }
    }
}

/**
 * A toggle that reads as part of the option above it rather than a setting
 * of its own: no icon, no divider, and pulled up close against its parent
 * instead of getting the same breathing room a full [SettingsRow] gets.
 */
@Composable
internal fun SettingsSubRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    badge: String? = null,
    subtitle: String? = null,
) {
    if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) return com.cosmotune.ui.settings.CosmoSettingsSubRow(title = title, checked = checked, onCheckedChange = onCheckedChange, badge = badge, subtitle = subtitle)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(start = ROW_INSET, end = ROW_INSET, top = 0.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Badge(badge)
                }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/** Marks the connection whose ceiling is actually in force right now. */
@Composable
internal fun Badge(text: String) {
    if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) return com.cosmotune.ui.settings.CosmoBadge(text = text)
    Text(
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(CosmoRadii.rXs))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
internal fun Chevron() {
    if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) return com.cosmotune.ui.settings.CosmoChevron()
    Icon(
        Icons.Rounded.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.size(20.dp),
    )
}

/** A continuous setting: label and current value on one line, track beneath. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SliderRow(
    icon: ImageVector,
    title: String,
    value: String,
    sliderValue: Float,
    onSliderValue: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    subtitle: String? = null,
) {
    if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) return com.cosmotune.ui.settings.CosmoSliderRow(icon = icon, title = title, value = value, sliderValue = sliderValue, onSliderValue = onSliderValue, valueRange = valueRange, steps = steps, subtitle = subtitle)
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.outline,
    )
    Column(Modifier.padding(start = ROW_INSET, end = ROW_INSET, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
            Spacer(Modifier.width(ICON_GAP))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = onSliderValue,
            valueRange = valueRange,
            steps = steps,
            colors = colors,
            // Bare track: the step ticks and the end-stop dot are noise when the
            // value is already spelled out on the line above.
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    colors = colors,
                    drawStopIndicator = null,
                    drawTick = { _, _ -> },
                )
            },
            modifier = Modifier.padding(start = ICON_SIZE + ICON_GAP),
        )
    }
}

/** Sign out: centered, accent-coloured, no glyph — the shape of a real one. */
@Composable
internal fun DestructiveRow(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) return com.cosmotune.ui.settings.CosmoDestructiveRow(label = label, enabled = enabled, onClick = onClick)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Sliding pill selector, for the handful of settings with two or three states. */
@Composable
internal fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) return com.cosmotune.ui.settings.CosmoSettingsSegmented(options = options, selectedIndex = selectedIndex, onSelect = onSelect, modifier = modifier, enabled = enabled)
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CosmoRadii.rSm))
            .background(MaterialTheme.colorScheme.outline)
            // Enough of a margin for the track to read as a track. At 2dp the
            // selected pill sat all but flush against the container's own edge,
            // so the two rounded rectangles merged into one shape and the
            // control stopped looking like something with a position in it.
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val chosen = index == selectedIndex
            val pill by animateColorAsState(
                targetValue = if (chosen) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
                animationSpec = tween(160),
                label = "segmentPill",
            )
            val labelColor by animateColorAsState(
                targetValue = if (chosen) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(160),
                label = "segmentLabel",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(CosmoRadii.rSm))
                    .background(pill)
                    .clickable(enabled = enabled) {
                        if (!chosen) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(index)
                        }
                    }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                    maxLines = 1,
                )
            }
        }
    }
}
