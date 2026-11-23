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

## Spotlight (live events, news and coming events)

`liveEvents(interest): Flow<List<LiveEvent>>` emits the cached list at once, then again after every read while it is collected,
on `SpotlightSchedule`'s cadence: while a match is live every 30 s with the sheet open (`LiveInterest.focus = SHEET`), every 15 s
with a match's detail open (`DETAIL`), every minute for the strip alone; with nothing live every ten minutes and at the next
kick-off. A higher `LiveInterest.refresh` reads the scores fresh at once. Collect it only while the app is in front: nothing is
read in the background. It never fails: a source that does not answer keeps its last good answer (with the time it was read, so
`SpotlightSchedule.isBehind` can say a live match's scores may be behind).

`LiveEvent`: `id` (stable: `football:espn:<league>:<id>`, `cricket:espn:<series>:<id>`, `news:...`, `event:wd:<Q>`), `kind`
(`CRICKET`, `FOOTBALL`, `SHOW`, `NEWS`, `EVENT`), `title`, `competition` (the country of a local story), `competitionId` ("eng.1",
a cricket series id), `teams` (`LiveTeam`: `name`, `shortName`, `logoUrl` (the crest), `score`, `country`, `batting`, `id`,
`colour`), `scoreText`, `statusText` ("India lead by 197 runs", "Full time", a story's summary), `status` (`LIVE`, `UPCOMING`,
`FINISHED`, `FEATURED` for news: a story is never live), `startMs`, `endMs`, `clock` (football's `MatchClock`: phase, ESPN's clock
in seconds, display, period, when read; `SpotlightClock.minute` keeps it going between reads), `incidents` (goals and cards with
side, minute and player), `venue`, `format` ("Test · Day 3"), `batting` ("Gill 104*, Jadeja 38*"), `outlets` (a story's outlets),
`major` (a curated league, an international, a story two outlets carry or Wikipedia lists), `local`, `watchOptions`, `source` and
`updatedAtMs`.

`matchDetail(event): MatchDetail?` reads a match's detail when its sheet opens: football's timeline (goals with the assist, cards,
substitutions, half-time and full time), paired stats and line-ups; cricket's innings (batting, bowling, partnerships), the last
balls and the notes.

Windows (`SpotlightWindows`): everything live; matches coming up in the next week; results of the last 24 hours (a Test from its
end); elections and summits of the next 60 days.

Order: matches first (live, upcoming, finished; inside each the big ones, then the viewer's country and language, then those
with a watch option, then upcoming by start and the rest newest first), then the news (world stories with most outlets first,
Wikipedia's picks, the country's own), then the coming events.

Where the data comes from (all free, keyless, read-only; unofficial ones are parsed defensively and left out when they fail):

| For | Source |
| :-- | :-- |
| Football | ESPN's site API: `soccer/<league>/scoreboard` (today's board, and yesterday's for the last day's results) for the Premier League, La Liga, Serie A, Bundesliga, Ligue 1, Champions League, Europa League, MLS, Indian Super League, World Cup, its qualifiers and internationals; `summary?event=` for the detail. OpenLigaDB stands in for the Bundesliga when ESPN fails. |
| Cricket | ESPN's live cricket header (`apis/v2/scoreboard/header?sport=cricket`): internationals, the big leagues (IPL, BBL, PSL, SA20, ILT20, The Hundred, ICC events) and the big countries' own competitions; `summary?event=` for the batters of a match in play and the scorecard. |
| World news | BBC World, The Guardian World, Al Jazeera, France 24, DW (RSS), the same story put together by its words and ranked by how many outlets carry it; Wikipedia's featured feed "In the news" (CC BY-SA). |
| The country | Google News for the viewer's country, its own tab. |
| Coming events | Wikidata's query service: national elections and summits with a day and an English Wikipedia page. |
| Shows | DD National's and DD Sports' live videos, and DD News' and DD India's live specials beside their 24x7 streams, from the DD plugin (when it is on). |

`DefaultWatchRepository.liveEventsNow()` answers once without polling.

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

## Device downloads and persistent episode batches

Hosts create one `WatchDownloads` per process and call `bindRepository(repository)` once. Its `batches` queue runs in the
download repository's scope, surviving Watch navigation. `batches.enqueue(title, selection, season, custom, maxHeight)`
snapshots exact episode targets from a season, all regular seasons, unwatched regular episodes or a custom set from the
current season. It skips completed files and already queued episodes; the folder and quality limit belong to the job.
`batches.items` drives progress; `pause`, `resume` (also retries failed episodes) and `remove` operate on persisted job IDs.
Removing a batch keeps its files. Only the unlocked active profile's batches run.

The queue persists stable targets and release hints, resolves addresses afresh when the app reopens, and keeps paused jobs
paused. It uses the same MP4/MKV/WebM direct-file downloader and free-space/folder checks as individual Watch downloads.
HLS, DASH, live channels and torrent offers remain outside this API. `WatchDownloads.pause(id)` cancels and joins the writer;
partial bytes resume with HTTP ranges for an unchanged address/header fingerprint, and restart safely when those change.
The process must be running; this API does not install an OS background transfer service.
