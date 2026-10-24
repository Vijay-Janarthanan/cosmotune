package com.cosmotune.ui.providers

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.cosmotune.music.data.model.Song
import com.cosmotune.providers.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Source-specific HTTP transport stays native; catalogue presentation and navigation are shared. */
@Composable
fun HttpAddonCatalogueEntry(identity: String, title: String, enabled: Boolean, revision: String,
    load: suspend (resource: String, id: String, force: Boolean) -> JsonObject,
    song: (ProviderTrack) -> Song, onPlay: (List<Song>, Int) -> Unit) {
    var open by remember(identity) { mutableStateOf(false) }
    TextButton(onClick = { open = true }, enabled = enabled) { Text("Browse $title") }
    if (!open) return
    var access by remember(identity, revision) { mutableStateOf<CatalogueBrowserAccess?>(null) }
    var error by remember(identity, revision) { mutableStateOf(false) }
    var attempt by remember(identity, revision) { mutableIntStateOf(0) }
    LaunchedEffect(enabled) { if (!enabled) open = false }
    LaunchedEffect(identity, revision, attempt) {
        error = false
        try {
            val manifest = load("manifest", "", attempt > 0)
            val supported = (manifest["resources"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
            val sourceIdentity = identity
            val sourceTitle = title
            val sourceRevision = revision
            val sourceEnabled = enabled
            val songFactory = song
            access = object : CatalogueBrowserAccess {
                override val identity = sourceIdentity
                override val title = sourceTitle
                override val revision = sourceRevision
                override val enabled = sourceEnabled
                override val settings = JsonObject(emptyMap())
                override val supportedKinds = supported.intersect(setOf("album", "artist"))
                override suspend fun search(query: String, force: Boolean): CatalogueSearchPage {
                    val result = load("search", query, force)
                    val pages = listOf("albums" to "album", "artists" to "artist").flatMap { (field, kind) ->
                        if (kind !in supported) emptyList() else (result[field] as? JsonArray).orEmpty().take(100).mapNotNull { item ->
                            (item as? JsonObject)?.let { ProviderCatalogue.collection(identity, kind, it, namespace = "addon") }
                        }
                    }
                    return CatalogueSearchPage(ProviderCatalogue.tracks(identity, result, "addon").map {
                        ProviderHub.artwork?.decorate("addon:$identity", it) ?: it
                    }, pages.distinctBy { it.key }.map { ProviderHub.artwork?.decorate("addon:$identity", it) ?: it })
                }
                override suspend fun catalogue(key: ProviderBrowseKey, force: Boolean): ProviderCollection {
                    require(key.namespace == "addon" && key.provider == identity && key.kind in supported)
                    val response = load(key.kind, key.id, force)
                    val raw = response[key.kind] as? JsonObject ?: response
                    val page = requireNotNull(ProviderCatalogue.collection(identity, key.kind, raw, key.id, "addon")) { "Incomplete source catalogue page" }
                    return ProviderHub.artwork?.decorate("addon:$identity", page) ?: page
                }
                override fun song(track: ProviderTrack): Song = songFactory(track)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
    }
    val ready = access
    if (ready != null) CatalogueBrowserDialog(ready, onDismiss = { open = false }, onPlay = onPlay)
    else AlertDialog(onDismissRequest = { open = false }, title = { Text(title) }, text = {
        if (error) Text("This source could not load its catalogue. Check its connection and required output.") else LinearProgressIndicator()
    }, confirmButton = { if (error) TextButton(onClick = { attempt++ }) { Text("Retry") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}
