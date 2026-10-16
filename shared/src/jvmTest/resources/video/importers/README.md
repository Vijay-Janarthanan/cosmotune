# Importer fixtures

Test data for the importers of Video mode (`com.cosmotune.video.importers`, spec 8.2). Every file here was written for
Cosmotune from the format descriptions in spec 2.7. Nothing is copied from another project, so there is nothing to attribute
and no licence to carry beyond Cosmotune's own (GPLv3, as the rest of the repository).

The channel and video ids are made up (24 and 11 characters, the real shapes); no channel, video or playlist here exists.
The names are invented.

| File | What it is | Format source (spec 2.7) |
| :-- | :-- | :-- |
| `newpipe-subscriptions.json` | A NewPipe subscriptions export: the three top-level keys, a channel of service 3, a repeated channel, a name with a comma, a quote and non-Latin text | NewPipe `ImportExportJsonHelper`, `SubscriptionData` |
| `newpipe-subscriptions-user.json` | An older export: `/user/`, `/c/` and `/@handle` addresses, a repeat, a playlist address, no `app_version` keys | the same, and LibreTube's reader (all keys optional) |
| `freetube-subscriptions.opml` | OPML in FreeTube's form: a folder outline, nested outlines, `title` or only `text`, entities, either quote, upper-case attribute names, a playlist feed, a repeat, a commented-out entry | FreeTube `DataSettings.vue`, Invidious `imports.cr` |
| `takeout-subscriptions.csv` | A Takeout `subscriptions.csv`: CRLF, `http://` addresses, titles with commas and quotes, a blank last line | Google Takeout, NewPipeExtractor `YoutubeSubscriptionExtractor` (by position) |
| `takeout-subscriptions-es.csv` | The same from an account in Spanish: a byte order mark and a header in another language | the same |
| `takeout-subscriptions-old.json` | The older Takeout JSON: `youtube#subscription` objects, the account's own `snippet.channelId` beside `snippet.resourceId.channelId` | the same |
| `watch-history.json` | A Takeout `watch-history.json`: watched videos (one twice), an ad (`details`), a removed video (no `titleUrl`), a YouTube Music entry, a search, a subtitle without an address | Google My Activity schema, FreeTube `DataSettings.vue` |
| `playlist-current.csv` | A current per-playlist file: `Video ID,Playlist Video Creation Timestamp`, one video twice | Google Data Portability schema (playlist videos) |
| `playlist-old.csv` | The older per-playlist file: a metadata block, a blank line, `Video Id,Time Added` | Invidious PR #3595, LibreTube `ImportHelper.kt` |
| `playlists-index.csv` | Takeout's `playlists.csv` index with the columns Google's data schema lists | Google Data Portability schema (playlists) |

Not here, on purpose, and built by the tests instead (`ImportFixtures.kt`): the ZIP archives (a NewPipe backup, Takeout archives)
and the NewPipe database, which the tests write with `sqlite-jdbc` using the Room schema version 9 tables and columns the importer
reads. The files are small and the tests state what they contain next to what they expect.

`playlists.csv`: the column layout is only documented by Google's schema (no real file was seen). The importer finds the columns by
their names (`Playlist ID`, `Playlist Title (Original)`, `Playlist Create Timestamp`, `Playlist update timestamp`), so another
column order or extra columns are fine; a different header gives no index and loses nothing but the id and the times.

## The upstream projects' own fixtures (not included)

`ThirdPartyFixturesTest` checks the importers on the files NewPipe and NewPipeExtractor (both GPLv3, as Cosmotune) use in their
own tests. They are not in this folder: the copy was refused while the task ran. To run that test, either copy these files here,
under the same names, or set the environment variable `VIDEO_IMPORT_FIXTURES` to the folders that hold them (separated like a class
path); without them each of its tests is skipped.

| File | Where it is |
| :-- | :-- |
| `import_export_test.json` | NewPipe, `app/src/test/resources/` |
| `db_ser_json.zip`, `nodb_noser_json.zip` | NewPipe, `app/src/test/resources/settings/` |
| `youtube_takeout_import_test.json`, `youtube_takeout_import_test_1.csv`, `youtube_takeout_import_test_1.zip`, `youtube_takeout_import_test_2.zip` | NewPipeExtractor, `extractor/src/test/resources/` |

`bjj.csv` (an old-layout playlist file saved during research; its origin and licence are not recorded) is not used: `playlist-old.csv`
covers the same layout.
