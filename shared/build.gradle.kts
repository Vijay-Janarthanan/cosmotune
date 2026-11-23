import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
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

    // Android and desktop are both JVM, but the default hierarchy gives them no source set in
    // common.
    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                // Compile-only: the two applications supply the runtime artifact themselves.
                compileOnly("com.microsoft.onnxruntime:onnxruntime:1.20.0")
                // The SMB library client (Local music on a shared folder); the phone and the desktop each ship it.
                compileOnly("com.hierynomus:smbj:0.15.0")
                // The network stack the lyrics providers and the YouTube Music
                // client run on, at the phone's versions — both applications
                // now call the same code, so they resolve the same libraries.
                api("io.ktor:ktor-client-core:3.5.2")
                api("io.ktor:ktor-client-okhttp:3.5.2")
                api("io.ktor:ktor-client-websockets:3.5.2")
                api("io.ktor:ktor-client-content-negotiation:3.5.2")
                api("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                api("org.jsoup:jsoup:1.22.2")
                // YouTube stream resolution, as the phone does it: InnerTubeX's
                // client catalog and cipher tiers, NewPipe as the fallback. NewPipe
                // is compile-only because the phone ships a trimmed jar of it.
                api("com.github.MetrolistGroup.innertubex:innertubex:v0.7.0")
                compileOnly("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
                // NewPipe's JavaScript helper runs on it (org.schabi.newpipe.extractor.utils.JavaScript in this module).
                implementation("io.github.dokar3:quickjs-kt:1.0.14")
            }
        }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)
        jvmMain.dependencies {
            // The SQLite rows other apps' backups are read with (com.cosmotune.media.sqlite), for Video and Music alike.
            implementation("org.xerial:sqlite-jdbc:3.47.1.0")
            // Android supplies ICU in the OS. Only desktop needs the Java implementation.
            implementation("com.ibm.icu:icu4j:76.1")
        }

        commonMain.dependencies {
            // The Video contracts (com.cosmotune.video) expose Flow and StateFlow in common code; jvmSharedMain declares the same version.
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            // Models in this module intentionally have no Android or Compose dependency.
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            // Google's Material Color Utilities (the image quantiser, the scorer and the HCT colour
            // space) for the cover palette in com.cosmotune.theme. Pure Kotlin, so it has no Compose
            // dependency. 4.1.1 is the newest release built on the Kotlin 2.3 line this build uses
            // (5.x needs the Kotlin 2.4 standard library).
            implementation("com.materialkolor:material-color-utilities:4.1.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation("com.squareup.okhttp3:mockwebserver:5.3.2")
            // Video mode's tests (com.cosmotune.video): NewPipe's own objects built in memory, the database import, coroutines.
            // Test classpath only: the main code keeps NewPipe compileOnly.
            implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
            implementation("org.xerial:sqlite-jdbc:3.47.1.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

android {
    // AGP does not inherit resources from the custom intermediate Kotlin source set.
    sourceSets.getByName("main").resources.srcDir("src/jvmSharedMain/resources")
    namespace = "com.cosmotune.music.shared"
    compileSdk = 36
}

// The tests run NewPipe the way the apps ship it: without Rhino, on this module's QuickJS JavaScript helper.
configurations.matching { it.name == "jvmTestRuntimeClasspath" }.configureEach { exclude(group = "org.mozilla") }

// Release contract tests read these files outside this module's compiled test classpath.
// Notes, workflow and version changes must invalidate an earlier passing test result.
tasks.withType<Test>().configureEach {
    inputs.files(rootProject.file("build.gradle.kts"), rootProject.file("desktopApp/build.gradle.kts"),
        rootProject.file("web/extension/package.json"), rootProject.file(".github/workflows/check.yml"),
        rootProject.file(".github/workflows/release.yml"), rootProject.file("README.md"))
    inputs.files(rootProject.fileTree("docs/releases") { include("*.md") })
    inputs.files(rootProject.fileTree("docs") { include("**/*.md", "**/*.json") })
    // The fork guard inspects every workflow, including newly added manual publishers and composite actions.
    inputs.files(rootProject.fileTree(".github") { include("workflows/*.yml", "workflows/*.yaml", "actions/**/*.yml", "actions/**/*.yaml") })
    inputs.files(rootProject.fileTree("desktopApp/src/main/kotlin") { include("**/*.kt") },
        rootProject.fileTree("providers/src/jvmSharedMain/kotlin") { include("**/*.kt") })
}
