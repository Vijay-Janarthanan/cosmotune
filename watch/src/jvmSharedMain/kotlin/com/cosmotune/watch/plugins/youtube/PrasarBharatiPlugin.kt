package com.cosmotune.watch.plugins.youtube

import com.cosmotune.video.source.VideoSource
import com.cosmotune.watch.plugin.BuiltInPlugins
import com.cosmotune.watch.plugin.PluginKind

/**
 * Prasar Bharati's own Doordarshan channels. Prasar Bharati streams DD free online on its official YouTube channels (its
 * own app, WAVES, is DRM-protected and needs signing in, and it publishes no open stream address), so each channel here is
 * the YouTube channel that prasarbharati.gov.in links from that channel's page, or, where the page links none, the verified
 * channel of that name. What plays is what the channel has live; DD National, DD Sports and the regional channels go live
 * for programmes and events and are labelled "Not 24/7".
 */
class PrasarBharatiPlugin(video: () -> VideoSource?, now: () -> Long = System::currentTimeMillis) : OfficialLivePlugin(
    BuiltInPlugins.PRASAR_BHARATI, PluginKind.PRASAR_BHARATI, NAME, "DD", CHANNELS, video, now,
) {
    companion object {
        const val NAME = "Prasar Bharati (DD)"
        /** DD Sports: the channel the live events strip checks for a match it carries. */
        const val DD_SPORTS = "DDSports"

        /** Checked 6 Nov 2026: each id is linked from prasarbharati.gov.in (marked PB) or carries YouTube's verified badge (V). */
        val CHANNELS = listOf(
            // National.
            OfficialLiveChannel("DDNews", "DD News", "UCKwucPzHZ7zCUIf7If-Wo1g", listOf("News"), listOf("hin"), iptvOrgId = "DDNews.in"), // PB, V
            OfficialLiveChannel("DDIndia", "DD India", "UCGDQNvybfDDeGTf4GtigXaw", listOf("News"), listOf("eng"), iptvOrgId = "DDIndia.in"), // PB, V
            OfficialLiveChannel("DDNational", "DD National", "UCSjPe5kinQtwcyHcFJyyMfw", listOf("Entertainment"), listOf("hin"), allDay = false, iptvOrgId = "DDNational.in"), // PB, V
            OfficialLiveChannel(DD_SPORTS, "DD Sports", "UCX2eNtU1ZG3x4OpdP2UIlJA", listOf("Sports"), listOf("hin", "eng"), allDay = false, iptvOrgId = "DDSports.in"), // PB, V
            OfficialLiveChannel("DDKisan", "DD Kisan", "UCnDfmcUyhgJp6xC1LmBLfUg", listOf("Education", "Outdoor"), listOf("hin"), iptvOrgId = "DDKisan.in"), // V
            OfficialLiveChannel("DDUrdu", "DD Urdu", "UCu9Fj9QRAg26ZY9DhF8HSrQ", listOf("General"), listOf("urd"), iptvOrgId = "DDUrdu.in"), // PB, V
            OfficialLiveChannel("DDBharati", "DD Bharati", "UC6LS-HxIAGYkL-1SOsNIGOw", listOf("Culture"), listOf("hin"), allDay = false, iptvOrgId = "DDBharati.in"), // PB
            // Regional.
            OfficialLiveChannel("DDTamil", "DD Tamil", "UC5-_zkjKabYd7tsLucKsriw", listOf("General"), listOf("tam"), allDay = false, iptvOrgId = "DDTamil.in"), // V
            OfficialLiveChannel("DDSaptagiri", "DD Saptagiri", "UCcsvAreQ1IxIjWlBSpNEOWg", listOf("General"), listOf("tel"), allDay = false, iptvOrgId = "DDSaptagiri.in"), // PB, V
            OfficialLiveChannel("DDSahyadri", "DD Sahyadri", "UC-bdz-5EeUPT7lp7oSg4D5w", listOf("General"), listOf("mar"), allDay = false, iptvOrgId = "DDSahyadri.in"), // PB, V
            OfficialLiveChannel("DDGirnar", "DD Girnar", "UCneBFse451PgKgXJTDqikYA", listOf("General"), listOf("guj"), allDay = false, iptvOrgId = "DDGirnar.in"), // PB, V
            OfficialLiveChannel("DDOdia", "DD Odia", "UCb2PljffkAo_19l8t68zP2w", listOf("General"), listOf("ori"), allDay = false, iptvOrgId = "DDOdia.in"), // PB, V
            OfficialLiveChannel("DDPunjabi", "DD Punjabi", "UC8GYp35wJTN2ituJr0y-7AQ", listOf("Culture"), listOf("pan"), allDay = false, iptvOrgId = "DDPunjabi.in"), // PB, V
            OfficialLiveChannel("DDUttarPradesh", "DD Uttar Pradesh", "UCxtPipsf2nUZ9xM33TPe93Q", listOf("General"), listOf("hin"), allDay = false, iptvOrgId = "DDUttarPradesh.in"), // PB, V
            OfficialLiveChannel("DDMadhyaPradesh", "DD Madhya Pradesh", "UCjL_oswCjWhUUZWnynQDRXw", listOf("General"), listOf("hin"), allDay = false, iptvOrgId = "DDMadhyaPradesh.in"), // V
            OfficialLiveChannel("DDAssam", "DD Assam (North East)", "UCOPD-952cc3r9yDQJUQAezQ", listOf("General"), listOf("asm"), allDay = false, iptvOrgId = "DDAssam.in"), // PB, V
            OfficialLiveChannel("DDArunPrabha", "DD Arun Prabha", "UCl2zJJzviTakA_nNtIy5gSw", listOf("General"), listOf("eng", "hin"), allDay = false, iptvOrgId = "DDArunPrabha.in"), // PB
        )
    }
}
