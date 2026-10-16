package com.cosmotune.video.ui.search

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.SearchHighlight
import com.cosmotune.ui.blocks.CosmoOverline
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_clear
import com.cosmotune.video.resources.video_recent_searches
import com.cosmotune.video.resources.video_remove_search
import com.cosmotune.video.resources.video_search_hint
import com.cosmotune.video.resources.video_use_suggestion
import com.cosmotune.video.source.VideoSource
import com.cosmotune.video.ui.blocks.videoClickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/*
 * The pages of a search before there are results (spec 5.3): the idle page with the recent searches, and the typing page with the
 * source's suggestions and the recent searches that match. Both are lists of the same 48 dp row.
 */

/** How long the typed text must stand still before the source is asked for suggestions (spec 5.3: 250 ms). */
internal const val SUGGESTION_DEBOUNCE_MS = 250L

/** One row of the typing page: the text to search, and whether it comes from the recent searches (else from the source). */
@Immutable
internal data class SuggestionRow(val text: String, val recent: Boolean)

/** Which rows the typing page shows, written once and run by the tests. */
internal object SuggestionRows {
    /** At most this many of the source's suggestions. */
    const val MAX_SUGGESTIONS = 8

    /** At most this many recent searches after them. */
    const val MAX_RECENT = 3

    /**
     * The source's [suggestions] first (without the typed text itself, without repeats, any case), then the [recents] that hold the
     * typed text and are not among them. Nothing typed gives no rows.
     */
    fun build(typed: String, suggestions: List<String>, recents: List<String>): List<SuggestionRow> {
        val text = typed.trim()
        if (text.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        seen += text.lowercase()
        val fromSource = suggestions.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && seen.add(it.lowercase()) }
            .take(MAX_SUGGESTIONS)
            .map { SuggestionRow(it, recent = false) }
            .toList()
        val fromRecent = recents.asSequence()
            .map { it.trim() }
            .filter { it.contains(text, ignoreCase = true) && seen.add(it.lowercase()) }
            .take(MAX_RECENT)
            .map { SuggestionRow(it, recent = true) }
            .toList()
        return fromSource + fromRecent
    }
}

/**
 * The source's suggestions for what is typed, while [active]: asked once the text has stood still for [SUGGESTION_DEBOUNCE_MS] (a
 * newer text cancels the wait), and the last answer stays up until the next one arrives. A failure is no suggestions, not an error.
 */
@Composable
internal fun rememberSuggestions(source: VideoSource, typed: String, active: Boolean): State<List<String>> {
    val query = typed.trim()
    val answer = remember(source) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(source, query, active) {
        if (!active || query.isEmpty()) {
            answer.value = emptyList()
            return@LaunchedEffect
        }
        delay(SUGGESTION_DEBOUNCE_MS)
        answer.value = try {
            source.suggestions(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
    return answer
}

// ---------------------------------------------------------------------------------------------------------------------------
// the rows

/**
 * The typing page's rows: a magnifier for a suggestion and a clock for a recent search, the typed part in weight 700
 * ([SearchHighlight.split]), and at the end the fill arrow that puts the text in the field to carry on typing from it. A tap
 * on the text searches it ([onPick]).
 */
internal fun LazyListScope.suggestionRows(rows: List<SuggestionRow>, typed: String, onPick: (String) -> Unit, onFill: (String) -> Unit) {
    // A list-level inset, not padding inside the first row, so the gap under the field is stable whatever that row shows.
    item(key = "suggestions:inset") { Spacer(Modifier.height(INSET)) }
    items(rows, key = { (if (it.recent) "recent:" else "suggest:") + it.text }) { row ->
        SearchTextRow(
            text = row.text,
            typed = typed,
            leading = if (row.recent) CosmoIcons.Clock else CosmoIcons.Search,
            onClick = { onPick(row.text) },
            trailingIcon = FillArrow,
            trailingDescription = stringResource(Res.string.video_use_suggestion, row.text),
            onTrailing = { onFill(row.text) },
        )
    }
}

/**
 * The idle page: the recent searches with "Clear" in the overline and a remove button on each row, or, when there are none, the empty
 * state that says what to search for.
 */
internal fun LazyListScope.recentRows(recents: List<String>, onPick: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit) {
    if (recents.isEmpty()) {
        item(key = "idle:empty") {
            CosmoEmptyState(message = stringResource(Res.string.video_search_hint), icon = CosmoIcons.Search)
        }
        return
    }
    item(key = "recent:header") {
        CosmoOverline(
            text = stringResource(Res.string.video_recent_searches),
            trailing = {
                CosmoButton(
                    text = stringResource(Res.string.video_clear),
                    onClick = onClear,
                    kind = CosmoButtonKind.TEXT,
                    size = CosmoButtonSize.SMALL,
                )
            },
        )
    }
    items(recents, key = { "recent:$it" }) { text ->
        SearchTextRow(
            text = text,
            typed = "",
            leading = CosmoIcons.Clock,
            onClick = { onPick(text) },
            trailingIcon = CosmoIcons.Close,
            trailingDescription = stringResource(Res.string.video_remove_search),
            onTrailing = { onRemove(text) },
        )
    }
}

/**
 * One 48 dp row: [leading] (20 dp, `textTertiary`), 14 dp of gap, [text] in 15.5 sp with the part [typed] covers in weight 700, and a
 * 40 dp plain button at the end. The text is the row's button (a tap, a hold's focus ring, a role); the end button is its own.
 */
@Composable
private fun SearchTextRow(
    text: String,
    typed: String,
    leading: ImageVector,
    onClick: () -> Unit,
    trailingIcon: ImageVector,
    trailingDescription: String,
    onTrailing: () -> Unit,
) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val shown = remember(text, typed) {
        val (bold, rest) = SearchHighlight.split(text, typed)
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            append(rest)
        }
    }
    val style = remember(type, text) {
        type.forText(
            TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Normal, fontSize = TEXT_SP.sp, lineHeight = TEXT_SP.sp * 1.3f),
            text,
        )
    }
    Row(Modifier.fillMaxWidth().heightIn(min = ROW_MIN), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = ROW_MIN)
                .padding(start = RING_ROOM)
                .videoClickable(CosmoRadii.md, onClick, null)
                .padding(start = CosmoSpacing.margin - RING_ROOM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(leading, contentDescription = null, tint = Color(palette.textTertiary), modifier = Modifier.size(LEADING_DP.dp))
            Spacer(Modifier.width(LEADING_GAP))
            Text(
                text = shown,
                style = style,
                color = Color(palette.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        // The end button's 48 dp target ends 4 dp from the edge, so its glyph stands near the page margin.
        CosmoIconButton(
            icon = trailingIcon,
            contentDescription = trailingDescription,
            onClick = onTrailing,
            size = END_BUTTON,
            modifier = Modifier.padding(end = END_INSET),
        )
    }
}

/** The arrow of "use this suggestion": `CosmoIcons.ArrowLeft` turned 45 degrees, so it points up and to the left, at the field. */
private val FillArrow: ImageVector by lazy {
    val arrow = CosmoIcons.ArrowLeft
    ImageVector.Builder(
        name = "arrow-up-left",
        defaultWidth = arrow.defaultWidth,
        defaultHeight = arrow.defaultHeight,
        viewportWidth = arrow.viewportWidth,
        viewportHeight = arrow.viewportHeight,
    ).apply {
        addGroup(name = "turn", rotate = 45f, pivotX = arrow.viewportWidth / 2f, pivotY = arrow.viewportHeight / 2f)
        for (node in arrow.root) {
            if (node !is VectorPath) continue
            addPath(
                pathData = node.pathData,
                pathFillType = node.pathFillType,
                fill = node.fill,
                stroke = node.stroke,
                strokeLineWidth = node.strokeLineWidth,
                strokeLineCap = node.strokeLineCap,
                strokeLineJoin = node.strokeLineJoin,
                strokeLineMiter = node.strokeLineMiter,
            )
        }
        clearGroup()
    }.build()
}

private val ROW_MIN = 48.dp
private val INSET = 8.dp
private val RING_ROOM = 8.dp
private val LEADING_GAP = 14.dp
private val END_BUTTON = 40.dp
private val END_INSET = 4.dp
private const val LEADING_DP = 20
private const val TEXT_SP = 15.5f
