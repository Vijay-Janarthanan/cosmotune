package com.cosmotune.providers

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.LikeStatus

data class ProviderHubState(val manager: ProviderManager? = null, val loading: Boolean = true, val error: String? = null,
    val library: SourceLibraryStore? = null)

/** Installed once by each application. Package code remains unloaded until a requested operation. */
object ProviderHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var directory: File? = null
    @Volatile private var revision = 0L
    @Volatile private var nativeAuthAdmission: (String) -> Boolean = { false }
    @Volatile private var nativeSessionCallback: String? = null
    fun configureNativeAuthentication(admitted: (String) -> Boolean, defaultCallback: String? = null) {
        require(defaultCallback == null || admitted(defaultCallback))
        nativeSessionCallback = defaultCallback
        nativeAuthAdmission = admitted
        state.value.manager?.configureNativeAuthentication(admitted, defaultCallback)
    }
    fun acceptsNativeAuthentication(input: String) = nativeAuthAdmission(input)
    suspend fun receiveNativeAuthentication(input: String): Boolean {
        if (!nativeAuthAdmission(input)) return false
        val ready = withTimeout(10_000) { state.first { !it.loading } }
        return ready.manager?.completeRoutedAuthentication(input) != null
    }
    private var artworkDirectory: File? = null
    private var libraryObserver: Job? = null
    private val knownSongs = LinkedHashMap<String, Song>(128, .75f, true)
    @Volatile var artwork: CatalogueArtwork? = null
        private set
    @Volatile private var httpArtwork = emptyMap<String, ArtworkAccess>()
    @Volatile private var httpAdmission: (String) -> Boolean = { true }

    /** Only configured origins are admitted as local artwork servers. No credentials are copied. */
    fun setHttpArtworkSources(sources: Map<String, ArtworkAccess>, admission: (String) -> Boolean = { true }) {
        val previous = httpArtwork
        httpAdmission = admission
        httpArtwork = sources.toMap()
        previous.filter { (id, policy) -> sources[id] != policy }.keys.forEach { artwork?.cancel("addon:$it") }
    }
    private fun artworkAccess(owner: String): ArtworkAccess? {
        if (owner.startsWith("addon:")) {
            val id = owner.removePrefix("addon:")
            return httpArtwork[id]?.let { if (httpAdmission(id)) it else it.copy(download = false) }
        }
        val installed = mutable.value.manager?.store?.state?.value?.installed?.find {
            "provider:${it.manifest.name}" == owner && it.enabled
        } ?: return null
        return ArtworkAccess(installed.allowArtwork, installed.manifest.permissions.network, installed.allowLocalNetwork,
            revision = "${installed.sha256}|${installed.allowArtwork}|${installed.allowLocalNetwork}")
    }
    private val mutable = MutableStateFlow(ProviderHubState())
    val state = mutable.asStateFlow()

    fun remember(song: Song): Song {
        if (SourceLibraryStore.ownsTrack(song.videoId)) synchronized(knownSongs) {
            knownSongs[song.videoId] = song
            while (knownSongs.size > 2_000) knownSongs.remove(knownSongs.keys.first())
        }
        return song
    }
    fun knownSong(id: String): Song? = synchronized(knownSongs) { knownSongs[id] }
        ?: state.value.library?.state?.value?.let { library -> library.ratings[id]?.track?.song()
            ?: (library.collections + library.followed).asSequence().flatMap { it.songs.asSequence() }.find { it.id == id }?.song() }
    suspend fun rate(id: String, status: LikeStatus) {
        val library = requireNotNull(state.value.library) { "Source library is unavailable" }
        val song = requireNotNull(knownSong(id)) { "Open this source recording again before rating it" }
        library.rate(song, status)
    }

    fun configure(root: File, force: Boolean = false, cache: File = File(root, "artwork-cache")) {
        val generation = synchronized(lock) {
            if (!force && directory?.absolutePath == root.absolutePath) return
            if (directory?.absolutePath != root.absolutePath) synchronized(knownSongs) { knownSongs.clear() }
            directory = root; artworkDirectory = cache; ++revision
        }
        mutable.value = mutable.value.copy(loading = true, error = null)
        scope.launch {
            com.cosmotune.music.data.recommendations.DiscoveryPreferences.configure(File(root, "discovery.json"))
            val images = runCatching { CatalogueArtwork(cache, ::artworkAccess) }.getOrNull()
            val library = runCatching { SourceLibraryStore(File(root, "library.bin")) }.getOrNull()
            val result = runCatching { ProviderManager(ProviderStore(root), images, remember = ::remember).also { manager ->
                manager.configureNativeAuthentication({ nativeAuthAdmission(it) }, nativeSessionCallback)
            } }
            var oldManager: ProviderManager? = null
            var oldImages: CatalogueArtwork? = null
            val accepted = synchronized(lock) {
                if (generation != revision) false else {
                    oldManager = mutable.value.manager; oldImages = artwork
                    val manager = result.getOrNull()
                    artwork = if (manager != null) images else null
                    mutable.value = if (manager != null) ProviderHubState(manager, loading = false,
                        error = manager.store.recoveryMessage ?: library?.recoveryMessage, library = library)
                        else ProviderHubState(loading = false, error = "Provider storage is unavailable. Check available space and retry.")
                    true
                }
            }
            if (!accepted || result.isFailure) { result.getOrNull()?.close(); images?.close() }
            // Closing VMs and their sockets can block. Never hold the UI configuration lock here.
            if (accepted) { oldManager?.close(); oldImages?.close() }
            if (accepted) synchronized(lock) {
                if (generation == revision) {
                    libraryObserver?.cancel()
                    if (library == null) com.cosmotune.music.data.LikeState.replaceSourceRatings(emptyMap())
                    libraryObserver = library?.let { current -> scope.launch { current.state.collect { saved ->
                        if (generation == revision) com.cosmotune.music.data.LikeState.replaceSourceRatings(saved.ratings.mapValues {
                            LikeStatus.valueOf(it.value.status)
                        })
                    } } }
                }
            }
        }
    }
    fun retry() {
        val target = synchronized(lock) { directory?.let { it to artworkDirectory } } ?: return
        configure(target.first, force = true, cache = target.second ?: File(target.first, "artwork-cache"))
    }
    fun sourceName(sourceId: String?): String? = sourceId?.takeIf { it.startsWith("sflx-") }?.removePrefix("sflx-")?.let { name ->
        state.value.manager?.store?.state?.value?.installed?.firstOrNull { it.manifest.name == name }?.manifest?.title
    }
}
