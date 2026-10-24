package com.cosmotune.ui.providers

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.cosmotune.providers.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The same save/follow operation is available on source pages without a YouTube account. */
@Composable
fun SourceLibraryButton(page: SourceCataloguePage) {
    val hub by ProviderHub.state.collectAsState()
    val empty = remember { MutableStateFlow(SourceLibraryState()) }
    val state by (hub.library?.state ?: empty).collectAsState()
    val id = page.collection.key.encode()
    val artist = page.collection.key.kind == "artist"
    val saved = if (artist) state.followed.any { it.id == id } else state.collections.any { it.id == id }
    var busy by remember(id) { mutableStateOf(false) }
    var failure by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    TextButton(enabled = !busy, onClick = {
        if (!busy) scope.launch {
            busy = true; failure = false
            try {
                val library = requireNotNull(hub.library) { "Source library unavailable" }
                if (artist) library.follow(page, !saved) else library.save(page, !saved)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure = true }
            finally { busy = false }
        }
    }) { Text(when { busy -> "Saving…"; failure -> "Save failed · Retry"; artist && saved -> "Following"; artist -> "Follow"; saved -> "Saved"; else -> "Save" }) }
}
