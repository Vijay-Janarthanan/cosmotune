import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Video mode: the second mode of the profile drawer (watching videos with no account and no sign-in). The screens, the player chrome and
 * the two platform players live here; the contracts, the pure logic and the source adapter live in :shared under
 * com.cosmotune.video. Music never depends on this module; the two applications each carry a thin host for it.
 *
 * Structure and versions follow :sharedUi (same Compose Multiplatform line, same targets, a `jvmSharedMain` both targets share).
 * Later tasks never edit this file: one that finds a dependency missing says so in its report and the controller adds it.
 */

/** Which platform this build is *for*, which is the host unless told otherwise (as desktopApp chooses its bytedeco classifier). */
val hostIsWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
val hostIsLinux = System.getProperty("os.name").contains("Linux", ignoreCase = true)
val hostIsMac = System.getProperty("os.name").contains("Mac", ignoreCase = true)
val targetOs: String = (providers.gradleProperty("cosmotune.target").orNull ?: when {
    hostIsWindows -> "windows"
    hostIsLinux -> "linux"
    hostIsMac -> "macos"
    else -> error("Cosmotune desktop supports Linux, Windows, and macOS only")
}).lowercase()
val hostArch = System.getProperty("os.arch").lowercase()
val isArm64 = hostArch == "aarch64" || hostArch == "arm64"

/** The libmpv natives of the build's platform: the artifact suffix of `mediamp-mpv-runtime-<platform>`. */
val mpvPlatform: String = when (targetOs) {
    "windows" -> "windows-x64"
    "linux" -> "linux-x64"
    "macos" -> if (isArm64) "macos-arm64" else "macos-x64"
    else -> error("Cosmotune desktop supports Linux, Windows, and macOS only")
}

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    applyDefaultHierarchyTemplate()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        // Both targets are JVMs, so the screens are written once against the JDK here rather than in commonMain.
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)

        commonMain.dependencies {
            // The look, the building blocks and the pages of the new interface; it brings :shared and Compose with it. Everyone.
            api(project(":sharedUi"))
            // The library, the settings and the exports are JSON. The stores (Y-7), the importers (Y-8), the player's caches.
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }

        // Media3 at the app's version (spec 2.5): one Media3 per APK, so a bump is Music's decision too.
        androidMain.dependencies {
            // The player itself. The Android engine (Y-11).
            implementation("androidx.media3:media3-exoplayer:1.11.0")
            // The media session and the background service. The playback service (Y-11), the system controls.
            implementation("androidx.media3:media3-session:1.11.0")
            // Player, MediaItem and the common types. The Android engine (Y-11), the service.
            implementation("androidx.media3:media3-common:1.11.0")
            // The HTTP data source on OkHttp, for googlevideo. The Android engine (Y-11), the download engine (Y-24).
            implementation("androidx.media3:media3-datasource-okhttp:1.11.0")
            // DASH playback (the side-loaded manifest of video-only plus audio streams). The Android engine (Y-11).
            implementation("androidx.media3:media3-exoplayer-dash:1.11.0")
            // HLS playback (live streams). The Android engine (Y-11).
            implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
            // PlayerSurface and ContentFrame for the picture. The Android surface provider (Y-11).
            // Its POM imports compose-bom 2026.05.00, which would lift every androidx.compose library in the app to 1.11.1 (material3
            // to 1.4.0) and stop Music compiling (AnchoredDraggable changed); the BOM is excluded so the app keeps the Compose it has.
            implementation("androidx.media3:media3-ui-compose:1.11.0") {
                exclude(group = "androidx.compose", module = "compose-bom")
            }
            // Joining a downloaded video-only and audio-only file without re-encoding. The Android download engine (Y-24).
            implementation("androidx.media3:media3-transformer:1.11.0")
            // Picture-in-picture listeners and the activity. The Android picture-in-picture state (Y-11).
            implementation("androidx.activity:activity-compose:1.9.3")
            // Services, notifications and the compat helpers. The two services (Y-11, Y-24).
            implementation("androidx.core:core-ktx:1.15.0")
        }

        jvmMain.dependencies {
            // libmpv's bindings. The desktop engine (Y-12); the same version the desktop already ships through jna-platform.
            implementation("net.java.dev.jna:jna:5.19.1")
            // The libmpv natives of the build's platform, from the mediamp runtime jars (spec Q1); taken without the Kotlin
            // bindings they list, which this module does not use. The desktop engine (Y-12).
            implementation("org.openani.mediamp:mediamp-mpv-runtime-$mpvPlatform:0.5.1") { isTransitive = false }
            // Remuxing a download's video and audio into one file. The desktop download engine (Y-24); the desktop app supplies the natives.
            compileOnly("org.bytedeco:ffmpeg:7.1.1-1.5.12")
            compileOnly("org.bytedeco:javacpp:1.5.12")
            // Reading the NewPipe database on import. The desktop importer (Y-8); the desktop already ships this version.
            implementation("org.xerial:sqlite-jdbc:3.47.1.0")
        }

        jvmTest.dependencies {
            // The opt-in live playback test resolves actual streams before handing them to libmpv.
            implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
            // The test framework and the structure tests. Every test of this module.
            implementation(kotlin("test"))
            implementation("junit:junit:4.13.2")
            // Skia's native library, so a test can build vectors and measure text without a window (as :sharedUi does).
            implementation(compose.desktop.currentOs)
            // A local server for the clients that talk HTTP, never the network (as :shared does). Downloads (Y-24), segments.
            implementation("com.squareup.okhttp3:mockwebserver:5.3.2")
            // runTest and virtual time, for the engines and clients that run on coroutines. The version :app already tests with.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

// The tests run without a display on every system, as they do on the Linux runner (and as :desktopApp's do), so a test that
// reaches for AWT fails here on Windows too instead of only in CI.
tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}

android {
    namespace = "com.cosmotune.video"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.cosmotune.video.resources"
    generateResClass = always
}
