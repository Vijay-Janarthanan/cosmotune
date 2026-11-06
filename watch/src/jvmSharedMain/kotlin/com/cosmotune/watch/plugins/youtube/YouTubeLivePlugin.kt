package com.cosmotune.watch.plugins.youtube

import com.cosmotune.video.source.VideoSource
import com.cosmotune.watch.plugin.BuiltInPlugins
import com.cosmotune.watch.plugin.PluginKind

/**
 * Broadcasters' own 24x7 live streams on YouTube: Tamil news first, then the main Telugu, Malayalam, Kannada, Hindi and
 * English news channels of India, and Sansad TV. Each entry is the broadcaster's own channel id (checked by hand: YouTube's
 * verified badge on a channel of that name with the broadcaster's audience); what plays is the live video that channel has
 * on when Play is pressed. Pay channels' restreams are never listed; only the broadcasters' own free live streams are.
 */
class YouTubeLivePlugin(video: () -> VideoSource?, now: () -> Long = System::currentTimeMillis) : OfficialLivePlugin(
    BuiltInPlugins.YOUTUBE_LIVE, PluginKind.YOUTUBE_LIVE, NAME, "YT LIVE", CHANNELS, video, now,
) {
    companion object {
        const val NAME = "Live on YouTube"
        private val NEWS = listOf("News")
        private fun news(slug: String, name: String, id: String, language: String, iptvOrgId: String? = null) =
            OfficialLiveChannel(slug, name, id, NEWS, listOf(language), iptvOrgId = iptvOrgId)

        /** Checked 6 Nov 2026 (verified badge, the broadcaster's name, live at the time unless noted). */
        val CHANNELS = listOf(
            // Tamil.
            news("SunNews", "Sun News", "UCYlh4lH762HvHt6mmiecyWQ", "tam", "SunNews.in"),
            news("PolimerNews", "Polimer News", "UC8Z-VjXBtDJTvq6aqkIskPg", "tam", "PolimerNews.in"),
            news("ThanthiTV", "Thanthi TV", "UC-JFyL0zDFOsPMpuWu39rPA", "tam", "ThanthiTV.in"),
            news("PuthiyaThalaimurai", "Puthiya Thalaimurai", "UCmyKnNRH0wH-r8I-ceP-dsg", "tam", "PuthiyaThalaimurai.in"),
            news("News18TamilNadu", "News18 Tamil Nadu", "UCat88i6_rELqI_prwvjspRA", "tam", "News18TamilNadu.in"),
            news("KalaignarSeithigal", "Kalaignar Seithigal", "UCcVF2Fth-qEA4T1Lhn3CgKg", "tam", "KalaignarSeithigal.in"),
            news("News7Tamil", "News7 Tamil", "UCGyZswzm4G-wEfRQHgMSAuw", "tam", "News7Tamil.in"),
            news("JayaPlus", "Jaya Plus", "UCOP4Gbw-T1ofcW8vyL89ZDw", "tam", "JayaPlus.in"),
            news("SathiyamNews", "Sathiyam News", "UC2ziCMHFPWkFHjocUMXT__Q", "tam", "SathiyamTV.in"),
            // Telugu.
            news("TV9Telugu", "TV9 Telugu", "UCPXTXMecYqnRKNdqdVOGSFg", "tel", "TV9Telugu.in"),
            news("NTVTelugu", "NTV Telugu", "UCumtYpCY26F6Jr3satUgMvA", "tel", "NTVTelugu.in"),
            news("ABNTelugu", "ABN Andhra Jyothy", "UC_2irx_BQR7RsBKmUV9fePQ", "tel", "ABNAndhraJyoti.in"),
            news("SakshiTV", "Sakshi TV", "UCZ9m4KOh8Ei60428xeGYDCQ", "tel", "SakshiTV.in"),
            news("V6News", "V6 News", "UCDCMjD1XIAsCZsYHNMGVcog", "tel", "V6News.in"),
            news("TV5News", "TV5 News", "UCAR3h_9fLV82N2FH4cE4RKw", "tel", "TV5News.in"),
            news("ETVAndhraPradesh", "ETV Andhra Pradesh", "UCJi8M0hRKjz8SLPvJKEVTOg", "tel", "ETVAndhraPradesh.in"),
            // Malayalam.
            news("AsianetNews", "Asianet News", "UCf8w5m0YsRa8MHQ5bwSGmbw", "mal", "AsianetNews.in"),
            news("ManoramaNews", "Manorama News", "UCP0uG-mcMImgKnJz-VjJZmQ", "mal", "ManoramaNews.in"),
            news("MathrubhumiNews", "Mathrubhumi News", "UCwXrBBZnIh2ER4lal6WbAHw", "mal", "MathrubhumiNews.in"),
            news("TwentyFourNews", "24 News", "UCup3etEdjyF1L3sRbU-rKLw", "mal", "24News.in"),
            news("MediaOne", "MediaOne", "UC-f7r46JhYv78q5pGrO6ivA", "mal", "MediaOne.in"),
            news("ReporterTV", "Reporter TV", "UCFx1nseXKTc1Culiu3neeSQ", "mal", "ReporterTV.in"),
            news("KairaliNews", "Kairali News", "UCnEvxaWfVL91XIYuyQRO5QA", "mal", "KairaliNews.in"),
            // Kannada.
            news("TV9Kannada", "TV9 Kannada", "UC8dnBi4WUErqYQHZ4PfsLTg", "kan", "TV9Kannada.in"),
            news("PublicTV", "Public TV", "UCl-OodciBGZ0k8K8rBZGe4w", "kan", "PublicTV.in"),
            news("News18Kannada", "News18 Kannada", "UCa-vioGhe2btBcZneaPonKA", "kan", "News18Kannada.in"),
            news("SuvarnaNews", "Asianet Suvarna News", "UCjElJyiXmQXnWmceQ1JyKrA", "kan", "AsianetSuvarnaNews.in"),
            // Hindi.
            news("AajTak", "Aaj Tak", "UCt4t-jeY85JegMlZ-E5UWtA", "hin", "AajTak.in"),
            news("ABPNews", "ABP News", "UCRWFSbif-RFENbBrSiez1DA", "hin", "ABPNews.in"),
            news("IndiaTV", "India TV", "UCttspZesZIDEwwpVIgoZtWQ", "hin", "IndiaTV.in"),
            news("ZeeNews", "Zee News", "UCIvaYmXn910QMdemBG3v1pQ", "hin", "ZeeNews.in"),
            news("NDTVIndia", "NDTV India", "UC9CYT9gSNLevX5ey2_6CK0Q", "hin", "NDTVIndia.in"),
            news("RepublicBharat", "Republic Bharat", "UC7wXt18f2iA3EDXeqAVuKng", "hin", "RepublicBharat.in"),
            news("News18India", "News18 India", "UCPP3etACgdUWvizcES1dJ8Q", "hin", "News18India.in"),
            news("TV9Bharatvarsh", "TV9 Bharatvarsh", "UCOutOIcn_oho8pyVN3Ng-Pg", "hin", "TV9Bharatvarsh.in"),
            // English.
            news("NDTV24x7", "NDTV 24x7", "UCZFMm1mMw0F81Z37aaEzTUA", "eng", "NDTV24x7.in"),
            news("IndiaToday", "India Today", "UCYPvAwZP8pZhSMW8qs7cVCw", "eng", "IndiaToday.in"),
            news("RepublicWorld", "Republic TV", "UCwqusr8YDwM-3mEYTDeJHzw", "eng", "RepublicTV.in"),
            news("WION", "WION", "UC_gUM8rL-Lrg6O3adPW9K1g", "eng", "WION.in"),
            news("TimesNow", "Times Now", "UC6RJ7-PaXg6TIH2BzZfTV7w", "eng", "TimesNow.in"),
            news("CNNNews18", "CNN-News18", "UCef1-8eOpJgud7szVPlZQAQ", "eng", "CNNNews18.in"),
            news("MirrorNow", "Mirror Now", "UCWCEYVwSqr7Epo6sSCfUgiw", "eng", "MirrorNow.in"),
            // Parliament.
            OfficialLiveChannel("SansadTV", "Sansad TV", "UCISgnSNwqQ2i8lhCun3KtQg", listOf("News", "Legislative"), listOf("hin", "eng"), iptvOrgId = "SansadTV1.in"),
        )
    }
}
