package com.cosmotune.providers

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderMediaProcessingTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun descriptor(key: String = "00112233445566778899aabbccddeeff", extension: String = "") = buildJsonObject {
        put("output_extension", extension)
        putJsonObject("decryption") { put("strategy", "ffmpeg.mov_key"); put("key", key) }
    }
    private class Tools(val write: (File, Long) -> Unit) : ProviderMediaTools {
        var calls = 0
        override fun probe(input: File, cancelled: () -> Boolean) = ProviderMediaInfo("flac", 44100, 2, 24, 1000, 100_000)
        override fun remux(input: File, output: File, key: String?, maximumBytes: Long, cancelled: () -> Boolean) {
            calls++; check(!cancelled()); assertEquals("00112233445566778899aabbccddeeff", key); write(output, maximumBytes)
        }
    }
    @Test fun blankProviderExtensionUsesFlacAndPublishesOnlyACompleteOutput() {
        val root = temporary.newFolder(); File(root, "input").writeText("encrypted")
        val files = ProviderFiles(root, ProviderNetwork(ProviderPermissions()), quota = 100)
        val tools = Tools { output, maximum -> assertEquals(91L, maximum); output.writeText("fLaC audio") }
        val output = ProviderMediaProcessing(files, tools) { false }.prepare(descriptor(), "input", "audio")
        assertEquals("audio-prepared.flac", output.name); assertEquals("fLaC audio", output.readText())
        assertEquals(1, tools.calls)
    }
    @Test fun invalidDescriptorsAndPathsCannotReachNativeCode() {
        val root = temporary.newFolder(); File(root, "input").writeText("encrypted")
        val tools = Tools { _, _ -> fail("Invalid descriptor must not reach native code") }
        val processor = ProviderMediaProcessing(ProviderFiles(root, ProviderNetwork(ProviderPermissions())), tools) { false }
        assertFails { processor.prepare(descriptor(key = "secret"), "input", "audio") }
        assertFails { processor.prepare(descriptor(extension = "../../outside"), "input", "audio") }
        assertFails { processor.prepare(descriptor(), "../outside", "audio") }
        assertEquals(0, tools.calls)
    }
    @Test fun sdkInspectionAndStreamCopyUseTheScopedAsynchronousHostAndRequireFilePermission() {
        val root = temporary.newFolder()
        val install = ProviderInstall(ProviderManifest("fixture", "1.0.0", type = listOf("download_provider"),
            permissions = ProviderPermissions(file = true)), "0".repeat(64))
        val tools = object : ProviderMediaTools {
            override fun probe(input: File, cancelled: () -> Boolean) = ProviderMediaInfo("flac", 44100, 2, 24, 1000, 100000)
            override fun remux(input: File, output: File, key: String?, maximumBytes: Long, cancelled: () -> Boolean) {
                assertNull(key); input.copyTo(output, overwrite = true)
            }
        }
        val store = ProviderStore(root, UnsealedProviderFiles)
        val host = ProviderMetadataHost(install, store, mediaTools = tools)
        fun call(method: String, args: JsonArray): JsonElement {
            var response = Json.parseToJsonElement(host.bridge(method, args.toString())).jsonObject
            repeat(200) {
                assertNull(response["failure"])
                val pending = response["pending"] ?: return response.getValue("value")
                response = Json.parseToJsonElement(host.bridge("work.poll", JsonArray(listOf(pending)).toString())).jsonObject
            }
            error("Native work did not complete")
        }
        try {
            call("file.write", buildJsonArray { add("input"); add("fLaC fixture") })
            for (api in listOf("ffmpeg.getInfo", "gobackend.getAudioQuality")) {
                val info = call(api, buildJsonArray { add("input") }).jsonObject
                assertEquals(JsonPrimitive(24), info["bitDepth"]); assertEquals(JsonPrimitive(44100), info["sampleRate"])
            }
            assertEquals(JsonPrimitive(true), call("ffmpeg.convert", buildJsonArray {
                add("input"); add("copy.flac"); addJsonObject { put("codec", "copy") }
            }).jsonObject["success"])
            assertEquals("fLaC fixture", File(root, "workspaces/fixture/copy.flac").readText())
        } finally { host.cancel() }
        val denied = ProviderMetadataHost(install.copy(manifest = install.manifest.copy(permissions = ProviderPermissions())), store, mediaTools = tools)
        try { assertTrue(Json.parseToJsonElement(denied.bridge("ffmpeg.getInfo", "[\"input\"]")).jsonObject.containsKey("failure")) }
        finally { denied.cancel() }
    }
    @Test fun failedOversizedAndCancelledWorkDeletesStagingAndPreservesThePreviousFile() {
        for (mode in listOf("failure", "quota", "cancel")) {
            val root = temporary.newFolder(); File(root, "input").writeText("encrypted")
            File(root, "audio-prepared.flac").writeText("previous")
            var cancelled = false
            val tools = Tools { output, _ ->
                output.writeText(if (mode == "quota") "x".repeat(100) else "partial")
                if (mode == "cancel") cancelled = true
                if (mode == "failure") error("Native failure")
            }
            val processor = ProviderMediaProcessing(ProviderFiles(root, ProviderNetwork(ProviderPermissions()), quota = 100), tools) { cancelled }
            assertFails { processor.prepare(descriptor(), "input", "audio") }
            assertEquals("previous", File(root, "audio-prepared.flac").readText())
            assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("media-") })
        }
    }
}
