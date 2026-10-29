// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        // Some compatible R8 builds are published only in the project's official CI archive.
        maven("https://storage.googleapis.com/r8-releases/raw") {
            content { includeModule("com.android.tools", "r8") }
        }
    }
    dependencies {
        /*
         * Override AGP 8.10.1's D8/R8. At least 8.11.32 is needed for the
         * NowPlayingScreen debug register-allocation/ART VerifyError fix.
         * The Android compression dependency also brings Kotlin 2.4 metadata;
         * Android's compatibility table requires R8 9.1.29 or later for it.
         * Keep this pin until AGP itself bundles a compatible compiler.
         * https://developer.android.com/build/kotlin-support
         */
        classpath("com.android.tools:r8:9.1.29")
    }
}
plugins {
    id("com.android.application") version "8.10.1" apply false
    id("com.android.library") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.3.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
    id("org.jetbrains.compose") version "1.10.3" apply false
}

// Phone and Wear OS releases must have matching identity, version and signing keys.
extra["cosmotuneVersionCode"] = 18
extra["cosmotuneVersionName"] = providers.gradleProperty("cosmotune.version").getOrElse("1.0.13")
extra["cosmotuneBetaSuffix"] = ""

subprojects {
    tasks.withType<Test>().configureEach {
        testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
