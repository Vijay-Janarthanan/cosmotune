package com.cosmotune.providers

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun args(vararg value: JsonElement) = JsonArray(value.toList())
    private fun text(value: String) = JsonPrimitive(value)
    private fun files(root: File = temporary.newFolder(), quota: Long = 1024 * 1024) = ProviderFiles(root, ProviderNetwork(ProviderPermissions()), quota)

    @Test fun filesStayInsideTheWorkspaceAndCannotAccessCredentialsOrSiblingProviders() {
        val root = temporary.newFolder()
        val host = files(root)
        for (path in listOf("../providers.json", "../../auth", File(root.parentFile, "other/song").absolutePath, ".", "song:stream", "\u0000")) {
            assertFailsWith<IllegalArgumentException> { host.path(path) }
        }
        val expected = File(root, "album/song.txt")
        host.call("write", args(text("album/song.txt"), text("A private file")))
        assertEquals("A private file", expected.readText())
        assertEquals("A private file", host.call("read", args(text(expected.absolutePath))).jsonObject["data"]?.jsonPrimitive?.content)
        host.call("copy", args(text("album/song.txt"), text("copied.txt")))
        host.call("move", args(text("copied.txt"), text("moved.txt")))
        assertEquals(JsonPrimitive(false), host.call("exists", args(text("copied.txt"))))
        assertEquals(JsonPrimitive(true), host.call("exists", args(text("moved.txt"))))
        host.call("delete", args(text("moved.txt")))
        assertFalse(File(root, "moved.txt").exists())
    }

    @Test fun binarySlicesKeepOffsetsAndQuotasPreventSparseFileExpansion() {
        val root = temporary.newFolder()
        val host = files(root, quota = 10)
        host.call("writeBytes", args(text("track.bin"), text("AAECAwQF")))
        val slice = host.call("readBytes", args(text("track.bin"), buildJsonObject { put("offset", 2); put("length", 2) })).jsonObject
        assertEquals("AgM=", slice["data"]?.jsonPrimitive?.content)
        assertEquals(2, slice["bytes_read"]?.jsonPrimitive?.int)
        assertEquals(false, slice["eof"]?.jsonPrimitive?.boolean)
        assertFailsWith<IllegalArgumentException> { host.call("writeBytes", args(text("track.bin"), text("AQ=="), buildJsonObject { put("offset", 20) })) }
        assertEquals(6, File(root, "track.bin").length())
        assertFailsWith<IllegalArgumentException> { host.call("readBytes", args(text("track.bin"), buildJsonObject { put("length", -1) })) }
        assertFailsWith<IllegalArgumentException> { host.call("download", args(text("https://example.com"), text("media.bin"), buildJsonObject { put("transform", "cannot-ignore-decryption") })) }
    }

    @Test fun linksCannotGrantAnotherDirectoryOrReplaceAnOutsideFile() {
        val root = temporary.newFolder()
        val outside = temporary.newFolder()
        val linked = File(root, "linked").toPath()
        val supported = runCatching { Files.createSymbolicLink(linked, outside.toPath()); true }.getOrDefault(false)
        assumeTrue("Symbolic links require platform permissions", supported)
        val host = files(root)
        assertFailsWith<IllegalArgumentException> { host.path("linked/secret") }
        assertFailsWith<IllegalArgumentException> { files(linked.toFile()) }
        assertFalse(File(outside, "secret").exists())
    }
}
