# Watch: the interfaces the screens call

Module `:watch` (package `com.cosmotune.watch`). `:sharedUi` depends on it with `api(project(":watch"))`, so every Watch page
in `sharedUi` can use these types directly. There is no Compose in this module.

For screens and previews use `FakeWatchRepository()` (in-memory, the titles and channels of the Watch board, no network).
Pass `latencyMs = 600` to see loading states. The real `WatchRepository` is created by the app hosts and handed down the
same way the other repositories are.

## The facade: `WatchRepository`

Every call is `suspend` and main-safe. A plugin that is off is never asked; a plugin that fails is left out, never fails
the call.

| Screen (board section) | Call | Returns |
| :-- | :-- | :-- |
| Watch home (1) | `home(HomeFilter.ALL / FILMS / SERIES / LIVE)` | `WatchHome`: `featured` (`Featured`: summary, badges, progress), `continueWatching`, `liveNow`, `rows` (`HomeRow`: id, title, subtitle, items, `wide`, `pageable`), `empty` |
| A row scrolled to the end | `rowPage(row.id, skip = row.items.size)` | more `TitleSummary` |
| Continue watching (live) | `continueWatching: StateFlow<List<ContinueItem>>` | `ContinueItem`: `summary`, `episode`, `episodeLabel` ("S2 E4"), `remainingMin`, `progress.fraction` |
| Live now | `liveNow(limit)` | `LiveNowItem(channel, now, next)`; `now.progressAt(nowMs)` for the line. Ranked by the viewer's country and language (`WatchPlace`: Tamil news first for a Tamil viewer in India); a DD or Live on YouTube channel appears only while it has something live |
| Live now strip: matches and live shows | `liveEvents()` | `Flow<List<LiveEvent>>`, see "Live events" below |
| Film page (2) | `title(key)` | `Title`: `summary` (name, year, runtimeMin, rating, genres, overview, posterUrl, backdropUrl, logoUrl), `certification`, `cast`, `moreLikeThis`, `trailers` (YouTube keys), `whereToWatch` |
| "Play from" list, best first (2) | `sources(targetOf(summary))` | `Flow<SourcesState>`: `streams` (ranked, `best` is first), `pending` plugins still answering, `errors`, `notFound`, `whereToWatch` |
| | `sourcesNow(target)` | the same, once everything has answered |
| Series page (3) | `title(seriesKey)` then `episodes(seriesKey, season)` | `Title.seasons` (`Season`: number, name, episodeCount; specials are 0 and last), `Episode` (key, number, name, stillUrl, runtimeMin, airDate) |
| Next up (the colour card's play) | `nextUp(seriesKey)` | `NextUp(episode, progress, resume)`, `label` "Resume S2 E4" |
| Episode progress | `progress(episode.key)` | `WatchProgress`: `fraction`, `remainingMs`, `completed` |
| Live TV chips (4) | `channelFacets()` | `ChannelFacets`: countries, languages, categories (`Facet(id, name, count)`), total |
| Channel list | `channels(ChannelFilter(country, language, category, favouritesOnly, query, pluginId))` | `List<Channel>` |
| Guide grid | `guide(channels, fromMs, toMs)` | `GuideWindow(rows = GuideRow(channel, programmes))`; `EpgProgramme.isOnAt(now)` marks "now" |
| Favourites | `favouriteChannels`, `setFavouriteChannel(key, on)` | |
| Search | `search(query)` | `SearchResults(films, series, channels)` across every plugin that is on |
| My list | `myList`, `setInMyList(summary, on)`, `isInMyList(key)` | |
| Plugins (5) | `plugins: StateFlow<List<PluginInfo>>` | built in first: `name`, `description`, `badge` ("IPTV", "PLUTO"), `enabled`, `builtIn`, `status`, `error` |
| A switch | `setPluginEnabled(id, on)` | |
| "Add a plugin or source" | `addUserSource(UserSourceSpec.StremioAddon(url) / Jellyfin(server, user, password) / Emby(...) / Plex(server, token) / M3u(url, epgUrl) / Xtream(server, user, password) / LocalFolder(path))` | `AddSourceResult.Added(plugin)` or `Failed(message)`; nothing is stored on failure |
| Remove / reorder a source | `removeUserSource(id)`, `moveUserSource(id, toIndex)` | |
| One-tap install link | `previewInstallLink(link)` then `installFromLink(link)` | `InstallPreview`: name, description, logo, `offers`, `alreadyInstalled`, `problem`, `installable` |
| Share a plugin pack | `exportPluginPack()` | a `cosmotune://watch/pack?d=...` link (also the QR payload): add-on manifest URLs only, never credentials |
| Import a pack | `readPluginPack(payload)` then `importPluginPack(payload, only)` | `PluginPackItem` list; `ImportPackResult(added, skipped, failed)` |
| "Find plugins" | `pluginDirectories` | `DirectoryLink(title, description, url)`: open in the browser; nothing is fetched from them |
| TMDB notice | `metadataSource` | `TMDB`, or `FALLBACK` when no TMDB key is configured (pages then come from Cinemeta and the plugins) |

`targetOf(summary)`, `targetOf(seriesTitle, episode)` and `targetOf(channel)` build the `PlayTarget` for Play and for the
Sources list.

## Live events (the "Live now" strip)

`liveEvents(): Flow<List<LiveEvent>>` emits the cached list at once, then again after every poll while it is collected
(about a minute while something is live, ten minutes otherwise). Collect it only while the strip is on screen. It never
fails: a score source that is not set up or does not answer is left out.

`LiveEvent`: `id` (stable), `kind` (`CRICKET`, `FOOTBALL`, `SHOW`), `title` ("India v Australia, 2nd T20I"), `competition`
("IPL 2027", "Premier League"), `teams` (`LiveTeam`: `name`, `shortName`, `logoUrl`, `score`, `country`), `scoreText` (the
whole score on one line, null before the start), `statusText` ("India need 4 runs from 10 balls", "67'", "Full time"),
`status` (`LIVE`, `UPCOMING`, `FINISHED`; `isLive`), `startMs`, `countries`, `languages`, `imageUrl`, `watchOptions`,
`source` (the attribution line: "CricketData.org", "football-data.org", "TheSportsDB", "YouTube") and `updatedAtMs`.

Window: everything live; upcoming in the next 24 hours; finished matches that started in the last 16 hours.

Order: live first, then upcoming, then finished; inside each, the viewer's country and language first (`WatchPlace`, the
same as Live TV: an India match or an IPL game for a viewer in India, a Tamil show for a Tamil viewer), then events with a
watch option, then upcoming by start time and the rest newest first.

Where the data comes from (all free, all read-only; a missing key leaves that service out, never fails):

| Sport | With a key | Key (local.properties or environment) | Without a key |
| :-- | :-- | :-- | :-- |
| Cricket | CricketData.org `currentMatches`: internationals and the major leagues (IPL, WPL, Big Bash, PSL, SA20, The Hundred, ICC events) with runs, wickets, overs and the status line; kept 15 minutes (the free key allows 100 calls a day) | `CRICKETDATA_API_KEY` (free at cricketdata.org) | TheSportsDB: the IPL's next match and last result |
| Football | football-data.org `/v4/matches`: Premier League, La Liga, Serie A, Bundesliga, Ligue 1, Eredivisie, Primeira Liga, Championship, Brasileirão, Champions League, Euros, World Cup, with the running score; kept 2 minutes | `FOOTBALL_DATA_API_KEY` (free at football-data.org/client/register) | TheSportsDB: each top league's and the ISL's next match and last result, no running score |
| Shows | DD National's and DD Sports' live videos, and DD News' and DD India's live specials beside their 24x7 streams, from the DD plugin (when it is on) | none | |

TheSportsDB uses its public test key unless `THESPORTSDB_API_KEY` is set. The desktop passes the keys as
`-Dcosmotune.cricketdata.key`, `-Dcosmotune.footballdata.key`, `-Dcosmotune.thesportsdb.key`; the phone and TV as
`BuildConfig` fields of the same names as the keys. `DefaultWatchRepository.liveEventsNow()` answers once without polling.

A match's watch option is found, not assumed: a live video on DD Sports or DD National whose title names both sides.

`watchOptions` lists only free streams our own plugins play (DD Sports or another DD channel, an official broadcaster's
YouTube live), best first: `WatchOption(label, target, channel, badge)`. Play one with `player.open(option.target, null)`;
`channel` is set when the option is a Live TV channel (for its logo). An event no free stream carries has no watch option:
show the score only. Pay services are never listed.

`FakeWatchRepository().liveEvents()` gives a live India v Australia T20 with DD Sports as its option, a live DD News YouTube
special, a live and a finished football match with no option, and an upcoming IPL match.

## Keys

`WatchKey` is the one id everywhere (progress, My list, rooms): `m:tmdb:603` film, `s:tmdb:1399` series,
`e:tmdb:1399:1:1` episode, `c:<plugin>:<channelId>` channel, `a:<plugin>:<id>` an item with no TMDB match (`a:ytlive:<videoId>` a single YouTube live video). Without a TMDB
key, films and series use `m:imdb:tt...` / `s:imdb:tt...`. `key.kind`, `key.seriesKey`, `key.season`, `key.episode` read it.

## Streams

`Stream`: `source` (`pluginId`, `pluginName`, `badge`), `label`, `description`, `qualityBadge` ("4K", "1080p"), `hdr`,
`audioLanguages`, `subtitles`, `sizeBytes`, `hasAds`, `isLive`, `isPlayable` (false for "where to watch" links that open in
the browser). Never show `url` or `headers`.

## Playing: `WatchPlayer`

`open(target, summary)` resolves, picks the best copy, resumes from saved progress and plays on the platform engine
(`engine`, the same `VideoPlayerEngine` Video mode draws; use its surface the way the Video player does). `state`
(`WatchPlayerState`) carries the stream, every copy found, the audio tracks and subtitles, `nextEpisode` with its countdown,
the failover message and errors. `switchStream`, `selectAudio`, `selectSubtitle(id or null)`, `searchSubtitles(languages)`,
`setSubtitleDelay`, `playNextEpisode`, `cancelNextEpisode`, `stop`. Play, pause, seek and position go to `engine`.

## Built-in plugins

`BuiltInPlugins.all`, in Plugins page order (default in brackets): iptv-org [on], Pluto TV [on], Samsung TV Plus [on],
Prasar Bharati (DD) [on], Live on YouTube [on], Plex free [on], Internet Archive [on], Free on YouTube [on], Public Domain Movies (Stremio) [off], OpenSubtitles (Stremio)
[on], WatchHub (Stremio) [off]. Nothing else is built in; no torrent, debrid or site-scraper plugin exists or may be added.

Prasar Bharati (DD), id `dd`, badge "DD": DD News, DD India, DD National, DD Sports, DD Kisan, DD Urdu, DD Bharati, DD Tamil
and the regional DD channels (`c:dd:DDTamil`). Prasar Bharati publishes no open stream address (its WAVES app is
DRM-protected), so each channel is its official YouTube channel and plays what that channel has live, through Video's
player like Free on YouTube. A channel that only goes live for programmes carries the label "Not 24/7"; when nothing is
live, it has no stream ("Not on your sources"). Its guide is the live video's title (on now, with an approximate end) and
the channel's scheduled streams (next).

Live on YouTube, id `ytlive`, badge "YT LIVE": broadcasters' own 24x7 news streams on YouTube, Tamil first (Sun News,
Polimer News, Thanthi TV, Puthiya Thalaimurai, News18 Tamil Nadu, Kalaignar Seithigal, News7 Tamil, Jaya Plus, Sathiyam),
then Telugu, Malayalam, Kannada, Hindi and English news and Sansad TV (`c:ytlive:SunNews`). It plays the same way as DD.
A single live video (a live special in the events strip) is `PlayTarget.Item(WatchKey.item(pluginId, videoId), pluginId,
videoId, name)` with `pluginId` `ytlive` or `dd`.

## Rooms ("Watch together")

`roomWatchItem(target, summary, stream, durationMs)` (in `com.cosmotune.ui.room`, the same as `WatchRoom.trackOf`) builds the
room item; hand it to `LocalRoomLauncher.current.playInRoom(item)` as the other kinds do. Its kind is `watch` and its
`videoId` the key; each member's device finds its own copy (the host's release first) and the room keeps time. A member
whose copy differs in length by more than a minute sees `WatchPlayerState.lengthNote`. A member on a version that does not
know a kind sees "Update Cosmotune to play this" instead of it being played as a song.

## Install links, packs, directories

`WatchLinks.pending` holds a link opened from outside (`stremio://...`, `cosmotune://watch/addon?url=...`,
`cosmotune://watch/pack?d=...`): show the Install sheet (`previewInstallLink`) or the pack sheet (`readPluginPack`), then call
`WatchLinks.handled()`. To offer one's add-ons to a room, post `WatchRoom.packOffer(exportPluginPack(), name)` as a chat
message; `WatchRoom.packIn(message.text)` finds the link in a received message for an "Import" button. The hosts expose the real repository as `DesktopWatch.repository` / `AndroidWatch.repository(context)`
and the player as `DesktopWatch.player` / `AndroidWatch.player(context)`.
