package com.cosmotune.listening

import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The native save dialog supplies the destination; metadata export never modifies a listed source. */
fun writeTextExportFile(selected: File, text: String, extension: String): File {
    require(extension in setOf("txt", "json", "m3u8"))
    val proposed = selected.absoluteFile
    val parent = requireNotNull(proposed.parentFile).canonicalFile
    require(parent.isDirectory) { "Choose an existing destination folder" }
    val name = if (proposed.extension.equals(extension, true)) proposed.name else proposed.name + "." + extension
    val target = File(parent, name)
    require(!Files.isSymbolicLink(target.toPath()) && (!target.exists() || target.isFile)) { "Choose a regular export destination" }
    if (extension == "m3u8") require(text.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }.none { line ->
        runCatching {
            val source = File(URI(line)).canonicalFile
            source == target.canonicalFile || target.exists() && Files.isSameFile(source.toPath(), target.toPath())
        }.getOrDefault(false)
    }) { "Choose a playlist filename that does not replace one of its source files" }
    val bytes = text.toByteArray(Charsets.UTF_8)
    require(bytes.size <= 16 * 1024 * 1024) { "Export exceeds 16 MB" }
    publishTextExport(target, bytes)
    return target
}

internal fun publishTextExport(target: File, bytes: ByteArray, writer: (File, ByteArray) -> Unit = { file, data ->
    FileOutputStream(file).use { it.write(data); it.fd.sync() }
}) {
    val temporary = File.createTempFile(".cosmotune-export-", ".part", target.parentFile)
    try {
        writer(temporary, bytes)
        try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    } finally { temporary.delete() }
}
