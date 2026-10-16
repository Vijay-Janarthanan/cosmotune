package com.cosmotune.video.library

import com.cosmotune.CosmoConfig
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Which platform's folder [videoLibraryDir] names. */
sealed interface LibraryPlatform {
    /** The phone: [filesDir] is the app's `Context.filesDir`. */
    data class Android(val filesDir: File) : LibraryPlatform

    /** The desktop (Windows, Linux, macOS): the XDG data folder rule, the same on every system. */
    data object Desktop : LibraryPlatform
}

/**
 * The folder Video keeps its files in (spec 8.1): Android `filesDir/video`; desktop `$XDG_DATA_HOME/cosmotune/video`, or
 * `~/.local/share/cosmotune/video` when that variable is empty or unset (the rule `DesktopAnalysisRuntime` uses for the
 * analysis folder, on Windows too). Nothing is created here: [FileLibraryFiles] makes the folder when it first writes.
 * [environment] and [userHome] are the machine's by default; tests pass their own.
 */
fun videoLibraryDir(
    platform: LibraryPlatform,
    environment: (String) -> String? = System::getenv,
    userHome: String = System.getProperty("user.home") ?: ".",
): File = when (platform) {
    is LibraryPlatform.Android -> File(platform.filesDir, "video")
    LibraryPlatform.Desktop -> {
        val base = environment("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$userHome/.local/share"
        File(File(base, CosmoConfig.STORAGE_NAME), "video")
    }
}

/**
 * The library's files in a folder (plan task Y-7). A write is atomic: the text goes to `<name>.tmp` in the same folder and is
 * forced to disk, then the temporary file is moved over the real one with `ATOMIC_MOVE` and `REPLACE_EXISTING`; where the
 * file system cannot move atomically it is replaced with a plain move. A crash leaves the old text or the new, never half of
 * it (spec decision V8). The folder is created on the first write. Text is UTF-8.
 */
class FileLibraryFiles internal constructor(
    private val dir: File,
    private val atomicMove: (from: Path, to: Path) -> Unit,
) : LibraryFiles {

    constructor(dir: File) : this(dir, ::moveAtomically)

    override fun read(name: String): String? {
        val path = pathOf(name)
        return try {
            String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        } catch (e: NoSuchFileException) {
            null
        }
    }

    @Synchronized
    override fun write(name: String, text: String) {
        val target = pathOf(name)
        Files.createDirectories(dir.toPath())
        val temporary = pathOf("$name.tmp")
        try {
            FileOutputStream(temporary.toFile()).use { out ->
                out.write(text.toByteArray(StandardCharsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            try {
                atomicMove(temporary, target)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            try {
                Files.deleteIfExists(temporary)
            } catch (ignored: Exception) {
                // the write's own failure is the one to report
            }
            throw e
        }
    }

    @Synchronized
    override fun rename(name: String, newName: String) {
        val source = pathOf(name)
        if (!Files.exists(source)) return
        Files.createDirectories(dir.toPath())
        Files.move(source, pathOf(newName), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun pathOf(name: String): Path {
        require(name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' }) {
            "not a plain file name: $name"
        }
        return dir.toPath().resolve(name)
    }
}

private fun moveAtomically(from: Path, to: Path) {
    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
