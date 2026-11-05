package com.cosmotune.video.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guard on the words of `:video` (plan task Y-1, step 8): every user-facing text is a `video_*` key in the one strings
 * file, none of them names the source (the video site or its short name) except the first-run notice, whose job it is to say
 * who this is not affiliated with, and no file of the module carries the name.
 *
 * Also pinned: the keys that the other tasks build against are all declared, so a rename or a deletion that would break a screen
 * built from the plan fails here first. Keys may be appended (a task that needs one more says so); none may go.
 */
class VideoStringsGuardTest {
    private val stringsPath = "video/src/commonMain/composeResources/values/strings.xml"

    private class Entry(val key: String, val kind: String, val texts: List<Pair<String, String>>)

    /** Every `<string>` and `<plurals>` of the file, in order: the plural's texts are keyed by quantity, a string's by `""`. */
    private val entries: List<Entry> by lazy {
        val file = File(VideoSourceContract.root, stringsPath)
        assertTrue(file.isFile, "$stringsPath exists")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val children = doc.documentElement.childNodes
        (0 until children.length).mapNotNull { children.item(it) as? Element }.map { element ->
            val key = element.getAttribute("name")
            when (element.tagName) {
                "string" -> Entry(key, "string", listOf("" to element.textContent))
                "plurals" -> {
                    val items = element.getElementsByTagName("item")
                    Entry(key, "plurals", (0 until items.length).map { (items.item(it) as Element).let { i -> i.getAttribute("quantity") to i.textContent } })
                }
                else -> error("unexpected element <${element.tagName}> in the strings file")
            }
        }
    }

    /** The rule: the text says the video site's name or its short form as a word. `returnyoutubedislike.com` is a host name, not that word. */
    private val namesTheSource = Regex("(?i)\\b(youtube|yt)\\b")

    @Test fun everyKeyStartsWithVideo() {
        val bad = entries.map { it.key }.filterNot { it.startsWith("video_") }
        assertTrue(bad.isEmpty(), "every key of this module starts with `video_`; these do not: $bad")
    }

    @Test fun noKeyIsDeclaredTwice() {
        val twice = entries.groupBy { it.key }.filterValues { it.size > 1 }.keys
        assertTrue(twice.isEmpty(), "a key declared twice is a build error in the resources: $twice")
    }

    @Test fun noTextNamesTheSourceExceptTheFirstRunNotice() {
        val bad = entries.filterNot { it.key.startsWith("video_disclaimer_") }
            .flatMap { e -> e.texts.filter { namesTheSource.containsMatchIn(it.second) }.map { "${e.key}: ${it.second}" } }
        assertTrue(bad.isEmpty(), "user-facing text says `Video`, `the video`, `the source`, never the site's name; found: $bad")
    }

    @Test fun theFirstRunNoticeIsWhereTheNameIsAllowedAndItDoesSayIt() {
        val notice = entries.single { it.key == "video_disclaimer_text" }.texts.single().second
        assertTrue(namesTheSource.containsMatchIn(notice), "the notice names who Cosmotune is not affiliated with")
    }

    @Test fun theRuleMeansWhatItSays() {
        assertTrue(namesTheSource.containsMatchIn("Open in YouTube"))
        assertTrue(namesTheSource.containsMatchIn("youtube"))
        assertTrue(namesTheSource.containsMatchIn("the YT app"))
        assertTrue(namesTheSource.containsMatchIn("Not YouTube."))
        assertFalse(namesTheSource.containsMatchIn("Counts from returnyoutubedislike.com"), "a host name that holds the word is not the word")
        assertFalse(namesTheSource.containsMatchIn("Video, no account"))
        assertFalse(namesTheSource.containsMatchIn("Highlights"), "`yt` inside a word is nothing")
    }

    @Test fun noFileOrFolderOfTheModuleIsNamedAfterTheSource() {
        val module = File(VideoSourceContract.root, "video")
        val named = module.walkTopDown()
            .onEnter { it.name != "build" }
            .filter { it != module && it.name.contains("youtube", ignoreCase = true) }
            .map { it.relativeTo(module).path }.toList()
        assertTrue(named.isEmpty(), "no file name of :video says the site's name: $named")
    }

    @Test fun noTextHasAnApostropheAQuoteOrAnEscape() {
        val bad = entries.flatMap { e -> e.texts.filter { (_, t) -> t.any { it == '\'' || it == '"' || it == '\\' } }.map { e.key } }
        assertTrue(bad.isEmpty(), "no apostrophes or quotes (the typographic apostrophe is fine): $bad")
    }

    @Test fun everyArgumentIsNumberedAndNothingElseIsAPercentSign() {
        val numbered = Regex("%\\d\\$[sd]")
        val bad = entries.flatMap { e -> e.texts.filter { (_, t) -> '%' in numbered.replace(t, "") }.map { e.key } }
        assertTrue(bad.isEmpty(), "arguments are `%1\$s` or `%1\$d`; a bare percent sign is not allowed: $bad")
    }

    @Test fun everyPluralHasOneAndOther() {
        val plurals = entries.filter { it.kind == "plurals" }
        assertTrue(plurals.isNotEmpty())
        for (p in plurals) assertEquals(listOf("one", "other"), p.texts.map { it.first }, "${p.key} has `one` then `other`")
    }

    @Test fun everyTextHasWordsAndNoLeadingOrTrailingSpace() {
        for (e in entries) for ((_, t) in e.texts) {
            assertTrue(t.isNotBlank(), "${e.key} is not empty")
            assertEquals(t.trim(), t, "${e.key} has no space at either end")
        }
    }

    @Test fun theKeysTheOtherTasksBuildOnAreAllDeclared() {
        val declared = listOf(
        // Shell
        """
        video_mode_title video_tab_home video_tab_subscriptions video_tab_library video_search video_search_hint
        video_drawer_history video_drawer_downloads video_drawer_settings video_drawer_import video_profile_name
        video_profile_subtitle video_placeholder
        """,
        // Home
        """
        video_home_overline video_kiosk_music video_kiosk_gaming video_kiosk_movies video_kiosk_podcasts video_kiosk_live
        video_shelf_continue video_shelf_subscriptions video_shelf_for_you video_list_error
        """,
        // Meta
        """
        video_views video_views_none video_watching video_age_minutes video_age_hours video_age_days video_age_weeks
        video_age_months video_age_years video_age_now video_subscribers video_count_videos video_duration_hm video_duration_m
        video_badge_live video_badge_upcoming video_more video_retry video_meta_join
        """,
        // Search
        """
        video_filter_all video_filter_videos video_filter_channels video_filter_playlists video_recent_searches video_clear
        video_remove_search video_no_results video_no_results_hint video_use_suggestion
        """,
        // Watch
        """
        video_likes_cd video_dislikes_cd video_save video_watch_later video_in_watch_later video_download video_share
        video_play_as_song video_like video_liked_state video_subscribe video_subscribed video_description video_show_more
        video_original_title video_chapters video_comments video_comments_off video_up_next video_autoplay video_play
        video_add_to_queue video_open_channel video_remove_from_history video_remove_from_playlist video_save_to_playlist
        video_new_playlist video_playlist_name video_create video_theatre video_full_window video_exit_full_window video_queue
        """,
        // Player
        """
        video_seek_back video_seek_forward video_seek_back_pill video_seek_forward_pill video_fullscreen video_exit_fullscreen
        video_pip video_captions video_off video_player_settings video_collapse video_quality video_quality_auto
        video_quality_auto_value video_speed video_speed_value video_audio_track video_audio_original video_audio_dubbed
        video_audio_descriptive video_loop video_stats video_speed_hold video_zoom_fill video_zoom_fit video_brightness
        video_volume video_go_live video_live video_previous video_next
        """,
        // Errors
        """
        video_error_stopped video_error_unavailable video_error_age video_error_kids video_error_private video_error_paid
        video_error_geo video_error_bot video_error_network video_error_parsing
        """,
        // Segments
        """
        video_skip_segment video_skipped video_undo video_muted_segment video_jump_highlight video_seg_sponsor
        video_seg_selfpromo video_seg_interaction video_seg_intro video_seg_outro video_seg_preview video_seg_hook
        video_seg_music_offtopic video_seg_filler video_seg_poi_highlight video_seg_exclusive_access video_seg_title_sponsor
        video_seg_title_selfpromo video_seg_title_interaction video_seg_title_intro video_seg_title_outro
        video_seg_title_preview video_seg_title_hook video_seg_title_music_offtopic video_seg_title_filler
        video_seg_title_poi_highlight video_seg_title_exclusive_access video_seg_skip video_seg_mute video_seg_ask
        video_seg_show video_seg_off
        """,
        // Stats
        """
        video_stats_resolution video_stats_codec video_stats_bitrate video_stats_buffer video_stats_dropped
        video_stats_decoder video_stats_hardware video_stats_software video_stats_client
        """,
        // Channel and playlist
        """
        video_channel_videos video_channel_shorts video_channel_live video_channel_playlists video_channel_about
        video_channel_joined video_channel_links video_playlist video_your_playlist video_saved_playlist video_play_all
        video_save_playlist video_unsave_playlist
        """,
        // Subscriptions
        """
        video_all video_updated video_channels_failed video_open_channel_chip video_no_subscriptions
        video_no_subscriptions_text video_import video_find_channels video_today video_yesterday video_this_week
        video_this_month video_older
        """,
        // Library
        """
        video_library video_history video_liked video_downloads video_channels video_saved_playlists video_import_export
        video_your_playlists video_clear_history video_clear_history_confirm video_pause_history video_history_paused
        video_search_history video_unsubscribe video_empty_history video_empty_watch_later video_empty_liked
        video_empty_playlist video_rename video_delete video_delete_playlist_confirm
        """,
        // Import and export
        """
        video_import_newpipe_subs video_import_newpipe_db video_import_takeout video_import_opml video_import_cosmotune
        video_import_choose video_import_summary video_import_skipped video_import_lookups video_import_confirm
        video_import_done video_import_failed video_export video_export_done
        """,
        // Comments
        """
        video_comments_top video_comments_newest video_replies video_pinned video_hearted video_comments_count video_creator
        """,
        // Downloads
        """
        video_dl_storage video_dl_waiting video_dl_running video_dl_joining video_dl_done video_dl_failed video_dl_choose
        video_dl_audio_only video_dl_estimate video_dl_open video_dl_wifi_wait video_dl_notification video_dl_channel
        """,
        // Downloads: sheet, batches, page, settings
        """
        video_dl_quality video_dl_auto video_dl_auto_means video_dl_subtitles_q video_dl_subtitle_language
        video_dl_auto_generated video_dl_each_original video_dl_remember video_dl_button video_dl_button_n
        video_dl_header_batch video_dl_latest video_dl_latest_count video_dl_all video_dl_toast_started video_dl_toast_change
        video_dl_already video_dl_added video_dl_view video_dl_paused video_dl_paused_battery video_dl_waiting_wifi
        video_dl_batch_progress video_dl_pause video_dl_resume video_dl_cancel video_dl_delete video_dl_save_copy
        video_dl_saved_copy video_dl_done_cd video_dl_filter_all video_dl_filter_videos video_dl_filter_shorts
        video_dl_filter_audio video_dl_sort_newest video_dl_sort_oldest video_dl_sort_title video_dl_sort_size video_dl_empty
        video_dl_subs_got video_dl_subs_none video_set_dl_group video_set_dl_ask video_set_dl_wifi video_set_dl_battery
        video_set_dl_folder video_set_dl_summary video_set_dl_reset
        """,
        // Settings
        """
        video_set_playback video_set_quality_wifi video_set_quality_mobile video_set_codec video_set_speed video_set_autoplay
        video_set_resume video_set_background video_set_pip video_set_double_tap video_set_seconds video_set_captions
        video_set_captions_language video_set_captions_size video_size_small video_size_medium video_size_large
        video_set_captions_background video_set_sponsorblock video_set_sponsorblock_on video_set_sponsorblock_bar
        video_set_sponsorblock_count video_set_sponsorblock_count_summary video_set_using_sponsorblock video_set_dearrow
        video_set_dearrow_titles video_set_dearrow_thumbs video_set_title_case video_case_as_given video_case_sentence
        video_case_title video_set_using_dearrow video_set_dislikes video_set_dislikes_show video_set_dislikes_credit
        video_set_content video_set_hide_shorts video_set_region video_set_language video_set_library video_set_clear_search
        video_set_export video_set_import video_set_downloads video_set_dl_quality video_set_about video_set_contacts
        video_set_contacts_text
        """,
        // Shorts
        """
        video_shorts video_see_all video_filter_shorts video_mute video_unmute video_short_liked video_next_short
        video_previous_short video_shorts_end
        """,
        // Legal
        """
        video_disclaimer_title video_disclaimer_text video_disclaimer_ok
        """,
        ).flatMap { it.trim().split(Regex("\\s+")) }
        assertTrue(declared.size > 300, "the plan declares well over 300 keys; this list has ${declared.size}")
        assertEquals(declared.size, declared.toSet().size, "the list names no key twice")
        val actual = entries.map { it.key }.toSet()
        val missing = declared.filterNot { it in actual }
        assertTrue(missing.isEmpty(), "keys the plan declares and a task may build on are gone: $missing")
    }

    @Test fun thePluralsAreStillPlurals() {
        val declared = "video_age_minutes video_age_hours video_age_days video_age_weeks video_age_months video_age_years video_count_videos video_channels_failed video_import_skipped video_import_lookups video_replies".split(" ")
        val actual = entries.filter { it.kind == "plurals" }.map { it.key }.toSet()
        assertTrue(actual.containsAll(declared), "plurals the plan declares: ${declared.filterNot { it in actual }}")
    }
}
