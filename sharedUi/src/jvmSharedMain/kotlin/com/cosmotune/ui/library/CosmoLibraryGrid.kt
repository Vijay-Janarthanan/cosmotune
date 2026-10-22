package com.cosmotune.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.design.BlockMetrics
import com.cosmotune.design.LibrarySegments
import com.cosmotune.ui.blocks.CosmoCard
import com.cosmotune.ui.blocks.cosmoTile
import com.cosmotune.ui.blocks.shelfKey
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.tonalFill
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.new_playlist
import com.cosmotune.music.sharedui.resources.saved_to_youtube_music
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.components.LIBRARY_GRID_SPACING
import com.cosmotune.music.ui.components.libraryGrid
import com.cosmotune.music.ui.screens.localizeCardSubtitle
import org.jetbrains.compose.resources.stringResource

/**
 * What a shelf's "Show all" opens onto in the new look: the shelf's cards at the same [libraryGrid] width, run down the screen
 * (12 dp across, 20 dp down, 16 dp from the sides). The parameters are [com.cosmotune.music.ui.screens.LibraryGridPage]'s.
 *
 * The cards are read live, not trusted to arrive sorted: this page is opened from a snapshot, and a pin toggled from its own
 * hold menu must move the card at once. Pinning wins over the default order, but an explicit sort reorders the whole grid, the
 * pinned cards included ([LibrarySegments.pinnedFirst], then [LibrarySegments.sorted]). With [onNewPlaylist], the first cell
 * is the one that makes a playlist. The cards are keyed on their id or title and place, so a feed that repeats one cannot crash
 * the grid.
 */
@Composable
fun CosmoLibraryGrid(
    shelf: HomeShelf,
    gridState: LazyGridState,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: (ShelfItem) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onNewPlaylist: (() -> Unit)? = null,
) {
    val pinnedPlaylists by AppUi.host.pinnedPlaylists.collectAsStateWithLifecycle()
    val librarySort by AppUi.host.librarySort.collectAsStateWithLifecycle()
    val items = remember(shelf, pinnedPlaylists, librarySort) {
        LibrarySegments.sorted(LibrarySegments.pinnedFirst(shelf.items, pinnedPlaylists), librarySort)
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val grid = libraryGrid(maxWidth - CosmoSpacing.margin * 2)
        LazyVerticalGrid(
            columns = GridCells.Fixed(grid.columns),
            state = gridState,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(LIBRARY_GRID_SPACING),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(horizontal = CosmoSpacing.margin),
        ) {
            if (onNewPlaylist != null) {
                item(key = "leading") { NewPlaylistCell(width = grid.cardWidth, onClick = onNewPlaylist) }
            }
            itemsIndexed(items, key = { index, item -> shelfKey(item, index) }) { _, item ->
                CosmoCard(
                    title = item.title,
                    imageUrl = item.thumbnailUrl,
                    onClick = { onItemClick(item) },
                    subtitle = localizeCardSubtitle(item.subtitle).takeIf { it.isNotBlank() },
                    width = grid.cardWidth,
                    onLongPress = { onItemLongPress(item) },
                )
            }
        }
    }
}

/**
 * The first cell of the Playlists grid: a [cosmoTile] square as wide as a cover, a 52 dp tonal circle holding the plus, and
 * under it "New playlist" and where it is saved.
 */
@Composable
private fun NewPlaylistCell(width: Dp, onClick: () -> Unit) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val shape = remember(width) { SuperellipseShape(BlockMetrics.cardRadiusDp(width.value).dp) }
    val title = stringResource(Res.string.new_playlist)
    val subtitle = stringResource(Res.string.saved_to_youtube_music)
    Box(
        Modifier
            .width(width)
            .aspectRatio(1f)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .cosmoTile(shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(
                Modifier
                    .padding(bottom = 8.dp)
                    .size(52.dp)
                    .drawBehind { drawCircle(tonalFill(colors)) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(CosmoIcons.Plus, contentDescription = null, tint = Color(palette.onAccentContainer), modifier = Modifier.size(26.dp))
            }
            Text(
                text = title,
                style = type.forText(
                    TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight(650), fontSize = 14.sp, lineHeight = 14.sp * 1.3f),
                    title,
                ),
                color = Color(palette.textPrimary),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = type.forText(
                    TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 12.5.sp * 1.35f),
                    subtitle,
                ),
                color = Color(palette.textSecondary),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
