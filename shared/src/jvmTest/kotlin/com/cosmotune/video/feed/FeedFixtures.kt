package com.cosmotune.video.feed

/** The recorded feeds of plan task Y-9 (live fetches of 2026-10-16, descriptions removed), read from the test resources. */
internal object FeedFixtures {
    /** MrBeast's channel feed: 15 entries, 9 of them linked to `/shorts/`. */
    const val MRBEAST_CHANNEL_ID = "UCX6OQ3DkcsbYNE6H8uQQuVA"

    /** Kurzgesagt's channel feed: 15 entries, 11 of them linked to `/shorts/`. */
    const val KURZGESAGT_CHANNEL_ID = "UCsXVk37bltHxD1rDPwtNM8Q"

    val mrBeastChannelFeed: String get() = read("channel_id_UCX6OQ3DkcsbYNE6H8uQQuVA.xml")
    val kurzgesagtChannelFeed: String get() = read("channel_id_UCsXVk37bltHxD1rDPwtNM8Q.xml")

    /** MrBeast's `UULF` (long-form uploads) playlist feed: 15 entries, none linked to `/shorts/`. */
    val mrBeastLongFormFeed: String get() = read("playlist_id_UULFX6OQ3DkcsbYNE6H8uQQuVA.xml")

    /** What the server answered with status 404: an HTML page. */
    val notFoundBody: String get() = read("404body.html")

    private fun read(name: String): String {
        val stream = checkNotNull(FeedFixtures::class.java.getResourceAsStream("/video/feed/$name")) { "video/feed/$name is missing" }
        return stream.use { it.readBytes().decodeToString() }
    }
}
