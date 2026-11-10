package com.cosmotune.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.watch.model.Episode
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Title
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import java.util.Locale

@Composable
internal fun WatchPlayerDetails(
    summary: TitleSummary,
    episode: Episode?,
    episodeTarget: PlayTarget.EpisodeOf?,
    actions: WatchActions,
    fonts: OrbitFonts,
    look: OrbitLook,
    frame: WatchFrame,
) {
    val title by key(summary.key) {
        produceState<Title?>(null, actions.repository) {
            value = runCatching { actions.repository.title(summary.key) }.getOrNull()
        }
    }
    val shown = summary.withTitleDetails(title)
    val currentEpisode by key(episodeTarget?.key) {
        produceState(episode, episode, actions.repository) {
            value = episode ?: episodeTarget?.let { target ->
                runCatching { actions.repository.episodes(summary.key, target.season) }.getOrNull()
                    ?.firstOrNull { it.number == target.episode }
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 24.dp, bottom = 32.dp)) {
        Column(Modifier.widthIn(max = 780.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OrbitLabel(if (shown.type == TitleType.SERIES) "About this series" else "About this film", fonts, look.dim)
            Text(shown.name, style = fonts.section, color = look.ink)
            Text(pageLine(shown, title), style = fonts.body, color = look.accent)
            WatchTitleFacts(shown, title, fonts, look)
            if (episodeTarget != null) {
                val episodeLine = listOfNotNull(
                    "S${episodeTarget.season} E${episodeTarget.episode}",
                    currentEpisode?.name ?: episodeTarget.episodeName,
                    runtimeText(currentEpisode?.runtimeMin),
                ).joinToString(" · ")
                Text(episodeLine, style = fonts.title, color = look.ink, modifier = Modifier.padding(top = 8.dp))
                currentEpisode?.overview?.takeIf { it.isNotBlank() }?.let { Text(it, style = fonts.body, color = look.dim) }
            }
        }
        title?.let { PeopleAndMore(it, actions, fonts, look, frame, inColumn = true) }
    }
}

/** Keep useful fields from the discovery card if the provider's detail response is sparse. */
internal fun TitleSummary.withTitleDetails(title: Title?): TitleSummary {
    val detail = title?.summary ?: return this
    return detail.copy(
        year = detail.year ?: year,
        overview = detail.overview?.takeIf { it.isNotBlank() } ?: overview,
        rating = detail.rating?.takeIf { it > 0 } ?: rating,
        genres = detail.genres.ifEmpty { genres },
        runtimeMin = detail.runtimeMin ?: runtimeMin,
        posterUrl = detail.posterUrl ?: posterUrl,
        backdropUrl = detail.backdropUrl ?: backdropUrl,
        logoUrl = detail.logoUrl ?: logoUrl,
        ids = ids.merge(detail.ids),
    )
}

@Composable
internal fun WatchTitleFacts(shown: TitleSummary, title: Title?, fonts: OrbitFonts, look: OrbitLook, showMissingPlot: Boolean = false) {
    val extra = listOfNotNull(
        title?.certification,
        title?.originalLanguage?.let { Locale.forLanguageTag(it).getDisplayLanguage() }?.takeIf { it.isNotBlank() },
        title?.status?.takeUnless { it == "Released" },
    ).joinToString(" · ")
    if (extra.isNotEmpty()) Text(extra, style = fonts.body, color = look.dim)
    val overview = shown.overview?.takeIf { it.isNotBlank() }
    if (overview != null) {
        Text(overview, style = fonts.body, color = look.ink)
    } else if (showMissingPlot) {
        Text("A synopsis is unavailable for this title.", style = fonts.body, color = look.dim)
    }
    if (shown.type == TitleType.SERIES && shown.genres.isNotEmpty()) {
        Text(shown.genres.joinToString(" · "), style = fonts.body, color = look.dim)
    }
    val credits = title?.crew.orEmpty().take(4).joinToString(" · ") { person ->
        listOfNotNull(person.name, person.role?.let { "($it)" }).joinToString(" ")
    }
    if (credits.isNotEmpty()) Text(credits, style = fonts.body, color = look.dim)
}
