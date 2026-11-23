package com.cosmotune.reading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.ui.*
import com.cosmotune.reading.data.ReadingLink
import com.cosmotune.reading.data.ReadingPublication
import com.cosmotune.reading.data.ReadingRepository
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * A comic or manga on the listener's Komga or Kavita, read page by page as the server sends each one (OPDS page streaming), without
 * downloading the whole book first. It starts where the server says the listener stopped.
 */
@Composable
internal fun BookPageStream(repository: ReadingRepository, publication: ReadingPublication, link: ReadingLink, onBack: () -> Unit) {
    val count = link.count.coerceAtLeast(1)
    val pager = rememberPagerState(initialPage = link.lastRead.coerceIn(0, count - 1)) { count }
    val look = orbitLook()
    val fonts = orbitFonts()
    val scope = rememberCoroutineScope()
    MangaBackHandler(true, onBack)
    Column(Modifier.fillMaxSize().background(look.ground).testTag("books:stream")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WatchRoundButton(CosmoIcons.ChevronLeft, "Back", look, onBack, size = 36.dp, modifier = Modifier.testTag("books:stream:back"))
            Text(publication.title, style = ReadType.rowTitle(), color = look.ink, maxLines = 1, modifier = Modifier.weight(1f))
            OrbitLabel("${pager.currentPage + 1} / $count", fonts, look.dim)
            WatchRoundButton(CosmoIcons.Previous, "Previous page", look, { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } },
                size = 36.dp, enabled = pager.currentPage > 0)
            WatchRoundButton(CosmoIcons.Next, "Next page", look, { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(count - 1)) } },
                size = 36.dp, enabled = pager.currentPage + 1 < count)
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1, key = { it }) { index ->
            var bytes by remember(index) { mutableStateOf<ByteArray?>(null) }
            var failed by remember(index) { mutableStateOf<String?>(null) }
            var retry by remember(index) { mutableIntStateOf(0) }
            LaunchedEffect(index, retry) {
                failed = null
                bytes = try { repository.streamPage(publication, link, index) } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = e.message ?: "This page could not be loaded."; null }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    failed != null -> ReadError(failed!!, onRetry = { retry++ }, title = "Page ${index + 1} did not load")
                    bytes == null -> CircularProgressIndicator()
                    else -> coil3.compose.AsyncImage(bytes, "Page ${index + 1} of ${publication.title}", Modifier.fillMaxSize().testTag("books:stream:page"),
                        contentScale = ContentScale.Fit)
                }
            }
        }
    }
}
