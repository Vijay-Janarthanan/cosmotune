import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** What the packages, the launcher and the settings footer all say. */
val appVersion: String = providers.gradleProperty("cosmotune.version").orNull
    ?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }
    ?: "1.0.5"

/** Which platform this build is *for*, which is the host unless told otherwise. */
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

// Windows installer metadata requires MAJOR.MINOR.BUILD even though the app's public version is
// displayed independently of the numeric installer build counter.
// The build number is the desktop version code; it also keeps "-beta1" out of the numeric installer version.
val desktopVersionCode = 7
val nativePackageVersion = appVersion.substringBefore('-').split('.').take(2).joinToString(".") + ".$desktopVersionCode"

// FFmpeg decodes audio; see DesktopAudioDecoder.
val javacppVersion = "1.5.12"
val ffmpegVersion = "7.1.1-$javacppVersion"
val nativeClassifier = when (targetOs) {
    "windows" -> "windows-x86_64"
    "linux" -> "linux-x86_64"
    "macos" -> if (isArm64) "macosx-arm64" else "macosx-x86_64"
    else -> error("Cosmotune desktop supports Linux, Windows, and macOS only")
}

val onnxRuntimeVersion = providers.gradleProperty("cosmotune.onnxruntime").orNull
    ?: if (targetOs == "macos" && !isArm64) "1.23.2" else "1.29.0"
val onnxNativePlatform = when (targetOs) {
    "windows" -> "win-x64"
    "linux" -> "linux-x64"
    else -> if (isArm64) "osx-aarch64" else "osx-x64"
}
val sqliteNativePlatforms = when (targetOs) {
    "windows" -> setOf("Windows/x86_64")
    "linux" -> setOf("Linux/x86_64", "Linux-Musl/x86_64")
    else -> setOf(if (isArm64) "Mac/aarch64" else "Mac/x86_64")
}

val torPlatform = when (targetOs) {
    "macos" -> if (isArm64) "macos-aarch64" else "macos-x86_64"
    "windows" -> "windows-x86_64"
    else -> "linux-x86_64"
}
val torVersion = "15.0.25"
val torSha256 = mapOf(
    "macos-aarch64" to "a3febb6179152b0b4f56a0f5cc57bf5cc8ecef7db35e7c3e9d5a044c8158c26c",
    "macos-x86_64" to "e1ae4d4a734d28bb590839188511deb3cebc26fcbce9df66d01b2c452060fe95",
    "windows-x86_64" to "c1988c701de2f24f7d098d1d6d7f507b58f27740d097f29c246e0524d639b252",
    "linux-x86_64" to "19cf18726eecb362e0f21d27afa9ea9efe5da928bfdfbb1ceb5fe54519e87f4a",
).getValue(torPlatform)
val torResources = layout.buildDirectory.dir("generated/tor-resources")
val prepareTorBundle by tasks.registering {
    inputs.property("platform", torPlatform)
    inputs.property("version", torVersion)
    inputs.property("sha256", torSha256)
    outputs.dir(torResources)
    doLast {
        val archive = layout.buildDirectory.file("tor-downloads/$torPlatform.tar.gz").get().asFile
        archive.parentFile.mkdirs()
        if (!archive.exists()) {
            check(!gradle.startParameter.isOffline) { "Tor bundle is not cached. Build once online to prepare $torPlatform." }
            val connection = URI("https://dist.torproject.org/torbrowser/$torVersion/tor-expert-bundle-$torPlatform-$torVersion.tar.gz")
                .toURL().openConnection().apply { connectTimeout = 30_000; readTimeout = 120_000 }
            val partial = File(archive.parentFile, archive.name + ".part")
            connection.getInputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            Files.move(partial.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == torSha256) { "Tor archive checksum mismatch: $archive" }
        val destination = torResources.get().dir("tor-bundle").asFile
        project.sync { from(tarTree(resources.gzip(archive))); into(destination) }
        val names = destination.walkTopDown().filter { it.isFile }.map { it.relativeTo(destination).invariantSeparatorsPath }.sorted().toList()
        File(destination, "files.txt").writeText(names.joinToString("\n"))
    }
}
tasks.named<ProcessResources>("processResources") {
    dependsOn(prepareTorBundle)
    from(torResources)
}
private fun localProperty(name: String): String = rootProject.file("local.properties")
    .takeIf { it.isFile }
    ?.readLines()
    ?.firstOrNull { it.startsWith("$name=") }
    ?.substringAfter('=')
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?: System.getenv(name).orEmpty().trim()

val desktopModuleIndexUrl = localProperty("MODULE_INDEX_URL")

// Last.fm signs every request with these, so signing in from inside the app
// needs them at hand. Supplied locally and never committed, as on Android.
val listenTogetherServer = localProperty("LISTEN_TOGETHER_SERVER")
val lastfmApiKey = localProperty("LASTFM_API_KEY")
val lastfmSecret = localProperty("LASTFM_SECRET")

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// These upstream jars contain natives for many operating systems. Keep their
// Java APIs, metadata and licences, and only the libraries this package can load.
val onnxRuntimeRaw by configurations.creating { isTransitive = false }
val sqliteRuntimeRaw by configurations.creating { isTransitive = false }
dependencies {
    onnxRuntimeRaw("com.microsoft.onnxruntime:onnxruntime:$onnxRuntimeVersion")
    sqliteRuntimeRaw("org.xerial:sqlite-jdbc:3.47.1.0")
}
val targetOnnxRuntime by tasks.registering(Jar::class) {
    archiveFileName.set("onnxruntime-$onnxRuntimeVersion-$onnxNativePlatform.jar")
    destinationDirectory.set(layout.buildDirectory.dir("target-runtime"))
    from(provider { zipTree(onnxRuntimeRaw.singleFile) })
    exclude { element ->
        val path = element.path
        (path.startsWith("ai/onnxruntime/native/") && !path.startsWith("ai/onnxruntime/native/$onnxNativePlatform/")) ||
            path.contains(".dSYM/") || path.endsWith(".pdb") ||
            Regex("META-INF/[^/]+\\.(SF|RSA|DSA)").matches(path)
    }
    doLast {
        ZipFile(archiveFile.get().asFile).use { zip ->
            check(zip.entries().asSequence().any { it.name.startsWith("ai/onnxruntime/native/$onnxNativePlatform/") && !it.isDirectory }) {
                "ONNX runtime has no natives for $onnxNativePlatform"
            }
        }
    }
}
val targetSqliteRuntime by tasks.registering(Jar::class) {
    archiveFileName.set("sqlite-jdbc-3.47.1.0-$nativeClassifier.jar")
    destinationDirectory.set(layout.buildDirectory.dir("target-runtime"))
    from(provider { zipTree(sqliteRuntimeRaw.singleFile) })
    exclude { element ->
        val path = element.path
        (path.startsWith("org/sqlite/native/") && sqliteNativePlatforms.none { path.startsWith("org/sqlite/native/$it/") }) ||
            Regex("META-INF/[^/]+\\.(SF|RSA|DSA)").matches(path)
    }
    doLast {
        ZipFile(archiveFile.get().asFile).use { zip ->
            check(zip.entries().asSequence().any { it.name.startsWith("org/sqlite/native/") && !it.isDirectory }) { "SQLite target natives missing" }
        }
    }
}
// :video also declares SQLite. Its compile dependency stays intact; the desktop
// runtime uses the platform-specific copy below, including in native smoke tests.
listOf("runtimeClasspath", "testRuntimeClasspath").forEach { name ->
    configurations.named(name) { exclude(group = "org.xerial", module = "sqlite-jdbc") }
}

dependencies {
    // Already on the classpath through the NewPipe extractor.
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.google.zxing:core:3.5.3")

    implementation(project(":shared"))
    implementation(project(":sharedUi"))
    implementation(project(":video"))
    implementation(project(":manga"))
    // BotGuard, for the PoTokens YouTube's web clients need: the phone runs it in an Android
    // WebView, the desktop in JavaFX's (WebKit). Per-platform jars carry the natives.
    val javafxClassifier = when (targetOs) {
        "windows" -> "win"
        "macos" -> if (isArm64) "mac-aarch64" else "mac"
        else -> "linux"
    }
    listOf("base", "graphics", "controls", "media", "web").forEach { module ->
        implementation("org.openjfx:javafx-$module:21.0.10:$javafxClassifier")
    }

    // The shared player loads artwork through Coil, as the phone does.
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    // Automix runs the same two models the Android build does. ONNX Runtime stopped shipping macOS
    // x86_64 natives in 1.24, so an Intel Mac build stays on 1.23.2, the last release that has them.
    // -Pcosmotune.onnxruntime=<version> overrides the choice (to try a version on any machine).
    implementation(files(targetOnnxRuntime.flatMap { it.archiveFile }))

    // Decoding, resampling and demuxing for the audio engine.
    implementation("org.bytedeco:javacpp:$javacppVersion")
    implementation("org.bytedeco:javacpp:$javacppVersion:$nativeClassifier")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion:$nativeClassifier")

    // The same backdrop blur the Android build uses for its floating bars.
    // On Windows, 1.7.2 keeps this Compose Desktop 1.10.3 app on the matching UI/text/animation
    // runtime. Preserve Linux's existing dependency graph exactly as it was.
    val desktopHazeVersion = if (targetOs == "windows") "1.7.2" else "1.7.3"
    implementation("dev.chrisbanes.haze:haze:$desktopHazeVersion")
    implementation("dev.chrisbanes.haze:haze-materials:$desktopHazeVersion")

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.components.resources)
    implementation(compose.materialIconsExtended)
    // The Skiko runtime rides in on this, and Skiko is per-platform.
    implementation(
        when (targetOs) {
            "windows" -> compose.desktop.windows_x64
            "macos" -> if (isArm64) compose.desktop.macos_arm64 else compose.desktop.macos_x64
            else -> compose.desktop.linux_x64
        }
    )

    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    // Listen Together speaks over a WebSocket; the Discord gateway already uses the same engine.
    implementation("io.ktor:ktor-client-websockets:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.slf4j:slf4j-nop:2.0.16")

    // Browsers keep their cookies in SQLite, and importing a session from one is how this app signs
    // in — see DesktopBrowserCookies.
    implementation(files(targetSqliteRuntime.flatMap { it.archiveFile }))
    // Chromium protects its Windows master key with DPAPI. JNA's platform helpers let the app
    // unwrap that key in the same Windows user session without invoking a shell or exposing it.
    implementation("net.java.dev.jna:jna-platform:5.19.1")

    // Linux desktop media controls use the standard session-bus MPRIS interfaces.
    implementation("com.github.hypfvieh:dbus-java-core:5.2.0")
    implementation("com.github.hypfvieh:dbus-java-transport-junixsocket:5.2.0")

    // Same URL deobfuscation helper used by Android's StreamResolver.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")

    // JVM equivalent of Android's QuickJS module host.
    implementation("org.graalvm.polyglot:polyglot:24.1.2")
    implementation("org.graalvm.polyglot:js:24.1.2")
    implementation("org.graalvm.js:js-language:24.1.2")

    testImplementation(kotlin("test"))
}

tasks.withType<Test> {
    systemProperty("java.awt.headless", "true")
    // Native aborts bypass JUnit's failure report; retain the last executing test in CI logs.
    testLogging.events("started", "failed")
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

/** Builds the Automix analyser from the same C++ the Android app compiles. */
// Per target, because both write the library under the same name and a Windows cross-build
// otherwise left a `.dll` where the next Linux run looked for its `.so`.
val analysisNativeDir = layout.buildDirectory.dir("native/$targetOs")

/** The tiny Windows frame bridge is independent of the optional analyser and SMTC libraries. */
val windowNativeDir = layout.buildDirectory.dir("native-window/$targetOs")

/**
 * Whether this build is producing the analyser for a platform that is not the one running the
 * build.
 */
val crossBuildingForWindows = targetOs == "windows" && !hostIsWindows

/**
 * An executable's absolute path, from the PATH this build was invoked with.
 *
 * Resolved here rather than left to the child process. Gradle runs external
 * commands from the daemon, whose own PATH is whatever it was started with, so
 * a daemon reused from a shell that had no cmake cannot find the one the
 * current shell does — it fails with a bare "No such file or directory" while
 * `cmake --version` works fine in the terminal beside it.
 */
/**
 * Whether a tool on the PATH can actually be started.
 *
 * A PATH entry can be a wrapper script whose own interpreter is gone; it is a file, it is
 * executable, and it fails only at exec time — which surfaces as a build failure rather than as the
 * missing-tool path this build already handles.
 */
fun runs(tool: File): Boolean = runCatching {
    val process = ProcessBuilder(tool.absolutePath, "--version")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        false
    } else {
        process.exitValue() == 0
    }
}.getOrDefault(false)

fun findOnPath(tool: String): File? {
    // Windows keeps the extension on the file and off the command line, so a bare name matches
    // nothing on disk: PATHEXT is the list a shell would have tried.
    val names = if (!hostIsWindows) {
        listOf(tool)
    } else {
        val extensions = System.getenv("PATHEXT").orEmpty()
            .ifBlank { ".COM;.EXE;.BAT;.CMD" }
            .split(';')
            .filter(String::isNotBlank)
        listOf(tool) + extensions.map { tool + it.lowercase() }
    }
    return System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .filter(String::isNotBlank)
        .flatMap { directory -> names.map { File(directory, it) } }
        // A PATHEXT match is executable by definition, and Windows' own canExecute is unreliable.
        .firstOrNull { it.isFile && (hostIsWindows || it.canExecute()) && runs(it) }
}

fun cmakeBinary(): String = findOnPath("cmake")?.absolutePath ?: "cmake"

val buildAnalysisNative by tasks.registering {
    val cmakeSource = project.file("native")
    val outputDir = analysisNativeDir.get().asFile
    val crossing = crossBuildingForWindows
    val toolchain = project.file("native/mingw-w64.cmake")
    inputs.dir(rootProject.file("native/analyzer"))
    inputs.dir(rootProject.file("app/src/main/cpp/jni"))
    inputs.dir(project.file("native"))
    inputs.property("target", targetOs)
    outputs.dir(outputDir)
    onlyIf {
        val cmake = findOnPath("cmake")
        if (cmake == null) {
            logger.lifecycle(
                "no usable cmake — building without the Automix analyser. If cmake is installed, " +
                    "a Gradle daemon started before it was on PATH may be in use: ./gradlew --stop",
            )
        }
        val compilerName = if (crossing) "x86_64-w64-mingw32-g++" else "g++"
        val compiler = targetOs != "windows" || findOnPath(compilerName) != null
        val ninja = targetOs != "windows" || findOnPath("ninja") != null
        if (cmake != null && (!compiler || !ninja)) {
            logger.lifecycle(
                "$compilerName or Ninja not found — the Windows build will have no Automix analyser. " +
                    "Install a MinGW C++ compiler and Ninja, then rebuild.",
            )
        }
        cmake != null && compiler && ninja
    }
    doLast {
        val javaHome = System.getProperty("java.home")
        val compiler = if (targetOs == "windows") {
            findOnPath(if (crossing) "x86_64-w64-mingw32-g++" else "g++")
                ?: error("the Windows C++ compiler disappeared while building the analyser")
        } else {
            null
        }
        val ninja = if (targetOs == "windows") {
            findOnPath("ninja") ?: error("Ninja disappeared while building the analyser")
        } else {
            null
        }
        providers.exec {
            commandLine(
                buildList {
                    addAll(listOf(cmakeBinary(), "-S", cmakeSource.absolutePath, "-B", outputDir.absolutePath))
                    if (targetOs == "windows") {
                        // The host may not have Visual Studio/nmake. Select the MinGW toolchain we
                        // checked above and discard any generator cached by an earlier configure.
                        addAll(
                            listOf(
                                "--fresh",
                                "-G", "Ninja",
                                "-DCMAKE_MAKE_PROGRAM=${ninja!!.absolutePath}",
                                "-DCMAKE_CXX_COMPILER=${compiler!!.absolutePath}",
                            ),
                        )
                    }
                    add("-DCMAKE_BUILD_TYPE=Release")
                    if (crossing) add("-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}")
                },
            )
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine(cmakeBinary(), "--build", outputDir.absolutePath, "--config", "Release", "-j", "4")
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
    }
}

/**
 * Builds the Win32/DWM frame bridge even when the much larger analyser build is skipped during a
 * desktop UI run. It is intentionally absent from Linux builds, whose window manager remains the
 * sole owner of decoration and behaviour.
 */
val buildWindowNative by tasks.registering {
    val cmakeSource = project.file("native/window")
    val outputDir = windowNativeDir.get().asFile
    val crossing = crossBuildingForWindows
    val toolchain = project.file("native/mingw-w64.cmake")
    inputs.dir(cmakeSource)
    inputs.property("target", targetOs)
    outputs.dir(outputDir)
    onlyIf {
        if (targetOs != "windows") return@onlyIf false
        val cmake = findOnPath("cmake")
        val compiler = findOnPath(if (crossing) "x86_64-w64-mingw32-g++" else "g++")
        val ninja = findOnPath("ninja")
        if (cmake == null || compiler == null || ninja == null) {
            logger.lifecycle(
                "cmake, Ninja, or a MinGW C++ compiler is unavailable — the Windows build will " +
                    "fall back to Compose's frameless window behaviour.",
            )
        }
        cmake != null && compiler != null && ninja != null
    }
    doLast {
        val javaHome = System.getProperty("java.home")
        val compiler = findOnPath(if (crossing) "x86_64-w64-mingw32-g++" else "g++")
            ?: error("MinGW C++ compiler disappeared while building the Windows frame")
        val ninja = findOnPath("ninja")
            ?: error("Ninja disappeared while building the Windows frame")
        providers.exec {
            commandLine(
                buildList {
                    addAll(
                        listOf(
                            cmakeBinary(),
                            "-S", cmakeSource.absolutePath,
                            "-B", outputDir.absolutePath,
                            "-G", "Ninja",
                            "-DCMAKE_MAKE_PROGRAM=${ninja.absolutePath}",
                            "-DCMAKE_CXX_COMPILER=${compiler.absolutePath}",
                            "-DCMAKE_BUILD_TYPE=Release",
                        ),
                    )
                    if (crossing) add("-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}")
                },
            )
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine(cmakeBinary(), "--build", outputDir.absolutePath, "-j", "4")
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildAnalysisNative, buildWindowNative)
    from(analysisNativeDir) {
        include("*.so", "*.dll", "*.dylib")
        into("native")
    }
    from(windowNativeDir) {
        include("*.dll")
        into("native")
    }
    // The Automix models, taken from the Android module rather than copied into this one.
    from(rootProject.file("app/src/main/assets")) {
        include("*.onnx")
        into("models")
    }
}

// Kotlin 2.4 no longer contributes this JVM target's compiler output to Gradle's plain `jar`
// task in this project. Compose's dev runner and the portable launcher both use that jar, so add
// the output explicitly instead of producing a resources-only application archive.
tasks.named<Jar>("jar") {
    val kotlinClasses = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin")
    dependsOn(kotlinClasses)
    from(kotlinClasses.flatMap { it.destinationDirectory })
}

/** A Windows build that can be assembled here, since an installer cannot be. */
val windowsPortableDir = layout.buildDirectory.dir("windows-portable")

val windowsPortableAssemble by tasks.registering {
    group = "distribution"
    description = "Lays out a Windows-runnable folder: application jars, Windows natives, launcher."
    dependsOn(tasks.named("jar"))
    val stage = windowsPortableDir.map { it.dir("Cosmotune") }
    val appJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val runtime = configurations.named("runtimeClasspath")
    val version = appVersion
    val moduleIndex = desktopModuleIndexUrl
    val lastfmKey = lastfmApiKey
    val lastfmSharedSecret = lastfmSecret
    val mainClass = composeMainClass
    // Declared, not implied.
    inputs.file(appJar)
    inputs.files(runtime)
    inputs.property("version", version)
    inputs.property("moduleIndex", moduleIndex)
    inputs.property("lastfm", lastfmKey.isNotBlank())
    inputs.property("mainClass", mainClass)
    outputs.dir(stage)
    doLast {
        val target = stage.get().asFile
        val lib = File(target, "lib")
        lib.deleteRecursively()
        lib.mkdirs()

        // Named by coordinate rather than by file name.
        runtime.get().incoming.artifacts.artifacts.forEach { artifact ->
            val id = artifact.id.componentIdentifier
            val group = (id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.group
            val name = if (group.isNullOrBlank()) artifact.file.name else "$group.${artifact.file.name}"
            artifact.file.copyTo(File(lib, name), overwrite = true)
        }
        appJar.get().asFile.copyTo(File(lib, "cosmotune-app.jar"), overwrite = true)

        // `lib\*` rather than a spelled-out classpath: the wildcard is what keeps this working when
        // a dependency is added, and the JVM is what expands it.
        File(target, "Cosmotune.bat").writeText(
            buildString {
                appendLine("@echo off")
                appendLine("setlocal")
                appendLine("rem Cosmotune, portable. Needs a Java 21+ runtime on PATH or in JAVA_HOME.")
                appendLine("set \"HERE=%~dp0\"")
                appendLine("set \"RUNNER=java\"")
                appendLine("if defined JAVA_HOME set \"RUNNER=%JAVA_HOME%\\bin\\java\"")
                append("\"%RUNNER%\" -Dcosmotune.version=$version")
                if (moduleIndex.isNotBlank()) append(" -Dcosmotune.module.index=$moduleIndex")
                if (lastfmKey.isNotBlank() && lastfmSharedSecret.isNotBlank()) {
                    append(" -Dcosmotune.lastfm.key=$lastfmKey -Dcosmotune.lastfm.secret=$lastfmSharedSecret")
                }
                appendLine(" -cp \"%HERE%lib\\*\" $mainClass %*")
                appendLine("endlocal")
            },
        )

        // What "it builds" is not the same as "it runs", and the difference is exactly what shipped
        // a broken archive.
        val jars = lib.listFiles { file: File -> file.name.endsWith(".jar") }.orEmpty()
        fun holds(entry: String) = jars.any { jar ->
            ZipFile(jar).use { zip -> zip.getEntry(entry) != null }
        }
        val problems = buildList {
            if (!holds("com/cosmotune/music/desktop/MainKt.class")) {
                add("the Cosmotune desktop entry point is missing")
            }
            if (!holds("androidx/compose/runtime/Composer.class")) {
                add("the Compose runtime is missing")
            }
            if (jars.none { it.name.contains("skiko-awt-runtime-windows") }) {
                add("the Windows Skiko renderer is missing")
            }
            if (jars.any { it.name.contains("skiko-awt-runtime-linux") }) {
                add("a Linux Skiko renderer is in a Windows archive")
            }
            if (!holds("org/bytedeco/ffmpeg/windows-x86_64/avformat.dll") &&
                jars.none { it.name.contains("ffmpeg") && it.name.contains("windows-x86_64") }
            ) {
                add("the Windows FFmpeg natives are missing")
            }
        }
        check(problems.isEmpty()) { "windowsPortable: " + problems.joinToString("; ") }

        val analyser = File(analysisNativeDir.get().asFile, "cosmotune_analysis.dll").isFile
        logger.lifecycle(
            if (analyser) {
                "windowsPortable: verified — Compose runtime, Windows renderer, Windows FFmpeg, Automix analyser."
            } else {
                "windowsPortable: verified, but no cosmotune_analysis.dll — Automix will not run in this archive. " +
                    "Rebuild with: nix-shell -p cmake pkgsCross.mingwW64.buildPackages.gcc"
            },
        )
    }
}

val windowsPortable by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Zips the portable Windows folder for copying to a Windows machine."
    dependsOn(windowsPortableAssemble)
    from(windowsPortableDir)
    archiveFileName.set("Cosmotune-$appVersion-windows-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    doLast { logger.lifecycle("windowsPortable: ${archiveFile.get().asFile.absolutePath}") }
}

val composeMainClass = "com.cosmotune.music.desktop.MainKt"

// Compose names the generated Res class after rootProject.name; the sources import it by its old package,
// so it is pinned here and renaming the project does not touch a single import.
compose.resources {
    packageOfResClass = "cosmotune.desktopapp.generated.resources"
}

compose.desktop {
    application {
        javaHome = javaToolchains.launcherFor(java.toolchain).map {
            it.metadata.installationPath.asFile.absolutePath
        }.get()
        mainClass = composeMainClass
        jvmArgs("-Xmx512m")
        jvmArgs("-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=20000", "-XX:G1PeriodicGCSystemLoadThreshold=0")
        jvmArgs("-Dcosmotune.version=$appVersion")
        if (desktopModuleIndexUrl.isNotBlank()) {
            jvmArgs("-Dcosmotune.module.index=$desktopModuleIndexUrl")
        }
        if (lastfmApiKey.isNotBlank() && lastfmSecret.isNotBlank()) {
            jvmArgs("-Dcosmotune.lastfm.key=$lastfmApiKey", "-Dcosmotune.lastfm.secret=$lastfmSecret")
        }
        if (listenTogetherServer.isNotBlank()) {
            jvmArgs("-Dcosmotune.listentogether.server=$listenTogetherServer")
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("packaging/proguard-rules.pro"))
        }

        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg,
                TargetFormat.Pkg,
                TargetFormat.Exe,
                TargetFormat.Msi,
                TargetFormat.Deb,
                TargetFormat.Rpm,
            )
            packageName = "Cosmotune"
            packageVersion = nativePackageVersion
            description = if (targetOs == "windows") "Cosmotune" else "Music and video, in your mood"
            vendor = "Cosmotune contributors"
            copyright = "Copyright © 2026 Cosmotune contributors, based on BitChord"

            modules(
                "java.base",
                "java.desktop",
                "java.instrument",
                "java.logging",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.prefs",
                "java.scripting",
                "java.sql",
                "java.xml",
                "jdk.crypto.ec",
                "jdk.dynalink",
                // Video mode's desktop player reads the video through a loopback range proxy (com.sun.net.httpserver);
                // without this module the slim production runtime cannot serve it, while development runs work.
                "jdk.httpserver",
                "jdk.accessibility",
                // JavaFX WebEngine returns JavaScript objects through netscape.javascript.JSObject.
                // Without this module the slim production runtime crashes in twkExecuteScript,
                // while development runs work because the full JDK already contains it.
                "jdk.jsobject",
                "jdk.localedata",
                "jdk.security.auth",
                "jdk.unsupported",
                "jdk.zipfs",
            )

            macOS {
                iconFile.set(project.file("packaging/icons/AppIcon.icns"))
                bundleID = "com.cosmotune.app"
                appCategory = "public.app-category.music"
                dockName = "Cosmotune"
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSRequiresAquaSystemAppearance</key>
                        <false/>
                    """.trimIndent()
                }
            }

            linux {
                iconFile.set(project.file("packaging/icons/AppIcon.png"))
                menuGroup = "Audio"
                appCategory = "AudioVideo;Audio;Player"
                debMaintainer = "vijaybfriendly@gmail.com"
                rpmLicenseType = "GPL-3.0"
            }

            windows {
                iconFile.set(project.file("packaging/icons/AppIcon.ico"))
                // Cosmotune's own installer identity, fixed for good: a changed value makes the installer
                // treat the next version as a different app. (Not the upstream app's, or installing would replace it.)
                upgradeUuid = "d4f2b6a1-3c58-4e7f-9a21-6b0c8e5d7f34"
                menuGroup = "Cosmotune"
                shortcut = true
                dirChooser = false
                perUserInstall = true
            }
        }
    }

}
