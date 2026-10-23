package com.cosmotune.music.desktop

/** What this build is made of, and on what terms. */
internal data class DesktopLicense(
    val name: String,
    val license: String,
    val url: String,
    val note: String = "",
)

/** Grouped the way someone checking a licence actually reads. */
internal data class DesktopLicenseGroup(
    val title: String,
    val entries: List<DesktopLicense>,
)

internal object DesktopLicenses {

    val groups: List<DesktopLicenseGroup> = listOf(
        DesktopLicenseGroup(
            "Recommendation data",
            listOf(
                DesktopLicense("Local recommendation model", "CC0, CC BY 4.0 and Apache-2.0 source data; see attribution", "https://github.com/Vijay-Janarthanan/cosmotune/blob/main/shared/src/jvmSharedMain/resources/recommendations/ATTRIBUTION.md", "ListenBrainz/MusicBrainz, Music4All-Onion, Yambda, FMA, Discogs, Wikidata and Open Opus. The model's full attribution and provenance accompany its resources."),
            ),
        ),
        DesktopLicenseGroup(
            "This application",
            listOf(
                DesktopLicense(
                    "Cosmotune",
                    "GPL-3.0-or-later",
                    "https://www.gnu.org/licenses/gpl-3.0.html",
                    "Your music, your way.",
                ),
            ),
        ),
        DesktopLicenseGroup(
            "Audio",
            listOf(
                DesktopLicense(
                    "FFmpeg",
                    "LGPL-2.1-or-later",
                    "https://ffmpeg.org",
                    "Decoding, demuxing and resampling. The LGPL build is used " +
                        "deliberately; the GPL-enabled variant is not shipped.",
                ),
                DesktopLicense(
                    "JavaCPP / JavaCPP Presets",
                    "Apache-2.0",
                    "https://github.com/bytedeco/javacpp",
                    "The bridge from the JVM to FFmpeg.",
                ),
                DesktopLicense(
                    "OpenJFX",
                    "GPL-2.0-with-classpath-exception",
                    "https://openjfx.io",
                    "Video playback for animated cover art. Audio no longer goes through it.",
                ),
            ),
        ),
        DesktopLicenseGroup(
            "Application framework",
            listOf(
                DesktopLicense("Kotlin and kotlinx", "Apache-2.0", "https://kotlinlang.org"),
                DesktopLicense(
                    "Compose Multiplatform",
                    "Apache-2.0",
                    "https://www.jetbrains.com/lp/compose-multiplatform/",
                    "The user interface, shared with the Android application.",
                ),
                DesktopLicense("Ktor", "Apache-2.0", "https://ktor.io"),
                DesktopLicense(
                    "Haze",
                    "Apache-2.0",
                    "https://github.com/chrisbanes/haze",
                    "The backdrop blur behind the floating bars, as on Android.",
                ),
                DesktopLicense("SLF4J", "MIT", "https://www.slf4j.org"),
                DesktopLicense(
                    "Archivo",
                    "OFL-1.1",
                    "https://github.com/Omnibus-Type/Archivo",
                    "The typeface of titles and display text in the new look, bundled as a variable font. Copyright 2020 The Archivo Project Authors.",
                ),
                DesktopLicense(
                    "Hanken Grotesk",
                    "OFL-1.1",
                    "https://github.com/marcologous/hanken-grotesk",
                    "The typeface of body text and labels in the new look, bundled as a variable font. Copyright 2021 The Hanken Grotesk Project Authors.",
                ),
            ),
        ),
        DesktopLicenseGroup(
            "Sources and integration",
            listOf(
                DesktopLicense(
                    "NewPipeExtractor",
                    "GPL-3.0-or-later",
                    "https://github.com/TeamNewPipe/NewPipeExtractor",
                    "Stream URL deobfuscation, the same component the Android build uses. " +
                        "Video mode also finds its videos, channels, playlists, comments and streams with it.",
                ),
                DesktopLicense(
                    "GraalVM JavaScript",
                    "UPL-1.0",
                    "https://www.graalvm.org",
                    "Runs source modules in a sandbox with no access to host classes.",
                ),
                DesktopLicense(
                    "dbus-java",
                    "MIT",
                    "https://github.com/hypfvieh/dbus-java",
                    "MPRIS media controls and the system tray icon and menu.",
                ),
                DesktopLicense(
                    "junixsocket",
                    "Apache-2.0",
                    "https://github.com/kohlschutter/junixsocket",
                    "Unix domain sockets for the session bus.",
                ),
                DesktopLicense(
                    "OpenCanvas",
                    "Apache-2.0",
                    "https://github.com/Vijay-Janarthanan/OpenCanvas",
                    "Finds a song's music video and lines it up with the song.",
                ),
                DesktopLicense(
                    "Material Color Utilities (MaterialKolor)",
                    "Apache-2.0 AND MIT",
                    "https://github.com/jordond/materialkolor",
                    "Picks the main colour of a cover and works in the HCT colour space. Google's code, converted to Kotlin Multiplatform.",
                ),
            ),
        ),
        DesktopLicenseGroup(
            "Video mode",
            listOf(
                DesktopLicense(
                    "libmpv",
                    "LGPL-2.1-or-later (Windows, macOS); GPL-3.0 (Linux)",
                    "https://mpv.io",
                    "Plays video. Taken unchanged from the mediamp runtime packages, with the libraries it is built from " +
                        "(FFmpeg, libass, libplacebo and others, each under its own licence), and loaded as a separate shared library.",
                ),
                DesktopLicense(
                    "mediamp runtime packages",
                    "Apache-2.0",
                    "https://github.com/open-ani/mediamp",
                    "The packages libmpv is taken from (org.openani.mediamp:mediamp-mpv-runtime).",
                ),
                DesktopLicense(
                    "SponsorBlock data",
                    "CC BY-NC-SA 4.0",
                    "https://sponsor.ajay.app/",
                    "Skippable segments, the work of SponsorBlock's contributors. Read over the network; can be switched off in Video settings.",
                ),
                DesktopLicense(
                    "DeArrow data",
                    "CC BY-NC-SA 4.0",
                    "https://dearrow.ajay.app/",
                    "Titles and thumbnails, the work of DeArrow's contributors. Read over the network; can be switched off in Video settings.",
                ),
                DesktopLicense(
                    "Return YouTube Dislike",
                    "Public API, within its stated limits",
                    "https://returnyoutubedislike.com/",
                    "Dislike counts: archived counts and an estimate from its users' votes. Can be switched off in Video settings.",
                ),
            ),
        ),
    )
}
