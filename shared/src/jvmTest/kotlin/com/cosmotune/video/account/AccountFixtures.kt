package com.cosmotune.video.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The saved youtubei answers of `/video/account/`. The anonymous ones (`search_mixed`, `channel_videos_grid`, `related_lockups`,
 * `playlist_page`, `playlist_lockups`, `home_signed_out`) are trimmed copies of real answers; the signed-in ones (`subscriptions_rich_grid`,
 * `continuation_*`, `channels_page`, `guide_subscriptions`, `legacy_playlist`, `library_playlists`, `changed_shape`, `empty_list`) are written
 * from the documented renderer shapes, because no account was available to record them.
 */
internal object AccountFixtures {
    fun json(name: String): JsonObject {
        val stream = checkNotNull(AccountFixtures::class.java.getResourceAsStream("/video/account/$name")) { "video/account/$name is missing" }
        return Json.parseToJsonElement(stream.use { it.readBytes().toString(Charsets.UTF_8) }) as JsonObject
    }
}
