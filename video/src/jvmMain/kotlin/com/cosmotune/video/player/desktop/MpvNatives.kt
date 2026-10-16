package com.cosmotune.video.player.desktop

import com.cosmotune.CosmoConfig
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Finds and loads libmpv for the desktop engine (spec 6.3, plan decision W8, open decision Q1).
 *
 * The library comes from the mediamp runtime jar of the build's platform (`org.openani.mediamp:mediamp-mpv-runtime-<platform>`,
 * taken without its dependencies), which holds at its root the native libraries and a listing `mpv-natives-<platform>.txt` that
 * names them in load order (dependencies first). For 0.5.1 on Windows that is 37 DLLs, among them `libmpv-2.dll` (the client
 * API with the render API, 54 `mpv_*` exports), FFmpeg (`avcodec-62.dll`, ...), libplacebo, libass, and `mediampv.dll`, which is
 * mediamp's own JNI glue: not extracted, the engine binds libmpv itself.
 *
 * On first use they are copied to `<cache>/cosmotune/mpv/<runtime version>/` (`<cache>` as the desktop's media cache decides:
 * `$XDG_CACHE_HOME`, else `~/.cache`), then JNA loads libmpv from there; on Windows its dependencies are found next to it.
 * Any failure gives null and one log line, and Video shows "Video playback is not available on this computer" (spec 6.3).
 */
object MpvNatives {
    /** The runtime artifacts' version, when the jar's name does not say it; the same as `video/build.gradle.kts` declares. */
    const val RUNTIME_VERSION = "0.5.1"

    private val lock = Any()
    @Volatile private var attempted = false
    @Volatile private var loaded: MpvLibrary? = null

    /** The runtime jar's platform name for this machine (`windows-x64`, `linux-x64`, `macos-arm64`, `macos-x64`), or null. */
    fun platform(): String? {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val arm64 = arch == "aarch64" || arch == "arm64"
        val x64 = arch == "amd64" || arch == "x86_64"
        return when {
            os.contains("windows") && x64 -> "windows-x64"
            os.contains("linux") && x64 -> "linux-x64"
            os.contains("mac") && arm64 -> "macos-arm64"
            os.contains("mac") && x64 -> "macos-x64"
            else -> null
        }
    }

    /** The listing resource of [platform] (`mpv-natives-<platform>.txt`, at the jar's root), or null when the jar is absent. */
    fun listingResource(platform: String): URL? = MpvNatives::class.java.classLoader?.getResource("mpv-natives-$platform.txt")

    /** The libraries the listing names, in load order, without mediamp's JNI glue. */
    fun libraries(listing: URL): List<String> =
        listing.openStream().use { it.readBytes().decodeToString() }.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.contains("mediampv") }

    /** Which of [names] is libmpv itself (`libmpv-2.dll`, `libmpv.so.2`, `libmpv.2.dylib`). */
    fun libmpvName(names: List<String>): String? = names.firstOrNull { it.startsWith("libmpv") }

    /** `$XDG_CACHE_HOME`, else `~/.cache` (the rule of the desktop's media cache). */
    fun cacheRoot(): Path = Path.of(
        System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank) ?: "${System.getProperty("user.home")}/.cache",
    )

    /** Where the libraries of [version] go under [cacheRoot]: `<cache>/cosmotune/mpv/<version>/`. */
    fun directory(cacheRoot: Path, version: String): Path = cacheRoot.resolve(CosmoConfig.STORAGE_NAME).resolve("mpv").resolve(version)

    /** The runtime version from the jar's file name in [listing]'s address, else [RUNTIME_VERSION]. */
    fun runtimeVersion(listing: URL, platform: String): String =
        Regex("""mediamp-mpv-runtime-${Regex.escape(platform)}-(\d+(?:\.\d+)+)""").find(listing.toString())?.groupValues?.get(1)
            ?: RUNTIME_VERSION

    /** libmpv, loaded once per process from the default cache; null (and one log line) when this computer cannot have it. */
    fun load(): MpvLibrary? = load(cacheRoot())

    /** As [load], with the cache under [cacheRoot] (tests use a scratch folder). The first call decides for the process. */
    internal fun load(cacheRoot: Path): MpvLibrary? {
        if (attempted) return loaded
        synchronized(lock) {
            if (attempted) return loaded
            loaded = try {
                tryLoad(cacheRoot)
            } catch (e: Throwable) {
                fail("${e.javaClass.simpleName}: ${e.message}")
            }
            attempted = true
            return loaded
        }
    }

    private fun tryLoad(cacheRoot: Path): MpvLibrary? {
        val platform = platform() ?: return fail("no libmpv for ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        val listing = listingResource(platform) ?: return fail("the mpv runtime for $platform is not on the classpath")
        val names = libraries(listing)
        val libmpv = libmpvName(names) ?: return fail("the mpv runtime for $platform lists no libmpv")
        val dir = directory(cacheRoot, runtimeVersion(listing, platform))
        extract(dir, names)
        val library = bind(dir, names, libmpv)
        val major = library.mpv_client_api_version().toLong() ushr 16
        if (major != 2L) return fail("libmpv client API $major is not 2")
        return library
    }

    /** Copies [names] from the classpath into [dir], skipping files already there with the right size. */
    internal fun extract(dir: Path, names: List<String>) {
        Files.createDirectories(dir)
        val loader = MpvNatives::class.java.classLoader
        for (name in names) {
            val resource = loader.getResource(name) ?: throw IllegalStateException("$name is listed but not in the jar")
            val target = dir.resolve(name)
            val connection = resource.openConnection()
            val size = connection.contentLengthLong
            if (Files.isRegularFile(target) && size >= 0 && Files.size(target) == size) continue
            val temporary = Files.createTempFile(dir, "$name.", ".part")
            try {
                connection.getInputStream().use { input -> Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING) }
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: java.io.IOException) {
                    // Another process may hold the file open (Windows locks a loaded DLL): keep it when it is complete.
                    if (!(Files.isRegularFile(target) && Files.size(target) == Files.size(temporary))) throw e
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    /**
     * Loads [libmpv] from [dir] by its base name through JNA's search path. If that fails (a dependency the system loader did
     * not find next to it), the listed libraries are loaded first in their order, then libmpv again.
     */
    private fun bind(dir: Path, names: List<String>, libmpv: String): MpvLibrary {
        val options = mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")
        val base = if (libmpv.endsWith(".dll", ignoreCase = true)) libmpv.dropLast(4) else libmpv
        NativeLibrary.addSearchPath(base, dir.toString())
        return try {
            Native.load(base, MpvLibrary::class.java, options)
        } catch (first: UnsatisfiedLinkError) {
            for (name in names) {
                if (name == libmpv) continue
                runCatching { NativeLibrary.getInstance(File(dir.toFile(), name).absolutePath) }
            }
            try {
                Native.load(File(dir.toFile(), libmpv).absolutePath, MpvLibrary::class.java, options)
            } catch (second: UnsatisfiedLinkError) {
                second.addSuppressed(first)
                throw second
            }
        }
    }

    private fun fail(reason: String): MpvLibrary? {
        System.err.println("Cosmotune: video playback is not available on this computer: $reason")
        return null
    }
}
