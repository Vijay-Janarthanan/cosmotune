package com.cosmotune.video.importers

/**
 * A whole Google Takeout ZIP in one step (spec 8.2: "the whole Takeout ZIP"): the channels, the watch history and your
 * playlists it holds, as one preview with the one summary ("123 channels, 4,210 history entries, 6 playlists; 3 skipped").
 *
 * What is read, by what the entry holds rather than by the language of its folder name:
 * - channels: [TakeoutSubscriptionsImporter.parseZip] (the first CSV that yields channels);
 * - history: every `.json` entry whose start holds `titleUrl` (the watch and search histories do; only watch addresses
 *   become entries, see [TakeoutHistoryImporter]);
 * - playlists: every `.csv` entry laid out like a playlist (either layout), named from its file name, with the ids and times
 *   of the index `playlists.csv` when that is in the ZIP.
 * Entries of other products, `__MACOSX` entries and the HTML history are not read.
 */
object TakeoutArchiveImporter {
    private const val SNIFF_BYTES = 4096

    /** Reads [zip]; [nowMs] is the follow time of the channels and the fallback time of the playlists. */
    fun parse(zip: ZipEntries, nowMs: Long): ImportPreview {
        val names = zip.names().filterNot { it.startsWith("__MACOSX/") }
        var result = TakeoutSubscriptionsImporter.parseZip(zip, nowMs)

        for (name in names.filter { it.lowercase().endsWith(".json") }) {
            val bytes = zip.read(name) ?: continue
            val start = bytes.copyOf(minOf(bytes.size, SNIFF_BYTES)).decodeToString()
            if (!start.contains("\"titleUrl\"")) continue
            result += TakeoutHistoryImporter.parse(bytes.decodeToString())
        }

        val index = ArrayList<PlaylistIndexEntry>()
        val playlistFiles = ArrayList<Pair<String, String>>()
        for (name in names.filter { it.lowercase().endsWith(".csv") }) {
            val text = zip.read(name)?.decodeToString() ?: continue
            when (TakeoutPlaylistsImporter.layoutOf(Csv.parse(text))) {
                TakeoutPlaylistsImporter.Layout.INDEX -> index += TakeoutPlaylistsImporter.parseIndex(text)
                TakeoutPlaylistsImporter.Layout.CURRENT, TakeoutPlaylistsImporter.Layout.OLD -> playlistFiles += name to text
                TakeoutPlaylistsImporter.Layout.OTHER -> Unit
            }
        }
        for ((name, text) in playlistFiles) result += TakeoutPlaylistsImporter.parse(name, text, nowMs, index)

        return result.copy(reason = null).explainedIfEmpty()
    }
}
