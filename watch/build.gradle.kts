import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Watch mode's data and playback layer: films, series and live TV. The models, the plugin contract, the repository the
 * screens call and its in-memory fake are in commonMain; the HTTP plugins (TMDB, Stremio-protocol add-ons, Jellyfin, Emby,
 * Plex, M3U, Xtream, XMLTV, iptv-org, Pluto TV, Samsung TV Plus, the Internet Archive) and the stores are in jvmSharedMain,
 * shared by the phone, the TV and the desktop. No Compose here: the screens live in :sharedUi, which depends on this module.
 *
 * Plugins speak read-only HTTP JSON (or M3U/XMLTV) protocols the app itself implements. No plugin code is downloaded or run,
 * and no torrent engine ships.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    applyDefaultHierarchyTemplate()
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    val jvmSharedMain by sourceSets.creating { dependsOn(sourceSets.commonMain.get()) }
    sourceSets.androidMain.get().dependsOn(jvmSharedMain)
    sourceSets.jvmMain.get().dependsOn(jvmSharedMain)
    sourceSets.commonMain.dependencies {
        // The Video contracts (VideoPlayerEngine, MediaInput) and the room models (PartyTrack) live in :shared.
        api(project(":shared"))
        api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    }
    // OkHttp arrives through :shared's ktor-client-okhttp; NewPipe is compile-only there too (the apps ship it).
    jvmSharedMain.dependencies {
        compileOnly("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
    }
    sourceSets.jvmMain.dependencies {
        // DPAPI for the sealed user-source credentials on Windows, the version the desktop already ships.
        implementation("net.java.dev.jna:jna-platform:5.19.1")
    }
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
    sourceSets.jvmTest.dependencies {
        implementation(kotlin("test"))
        implementation("junit:junit:4.13.2")
        implementation("com.squareup.okhttp3:mockwebserver:5.3.2")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
    }
}

android {
    namespace = "com.cosmotune.watch"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
}

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}
