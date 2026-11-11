package com.cosmotune.providers

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class WatchScriptRuntimeTest {
    @Test fun standardCryptoAndHtmlModulesWorkInsideTheWatchSandbox() = runBlocking {
        val root = Files.createTempDirectory("watch-js-modules").toFile()
        try {
            val script = """
                const cheerio = require('cheerio'), CryptoJS = require('crypto-js');
                module.exports.getStreams = async function() {
                    const dom = cheerio.load('<section><a href="https://example.com/video.mp4">Movie</a></section>');
                    return dom('section a').map(function(i, element) {
                        return {url:dom(element).attr('href'), title:dom(element).text(), hash:CryptoJS.SHA256('abc').toString()};
                    }).get();
                };
            """.trimIndent()
            val item = WatchScriptRuntime.streams(script, JsonObject(emptyMap()), listOf("example.com"), root).jsonArray.single().jsonObject
            assertEquals("https://example.com/video.mp4", item["url"]?.jsonPrimitive?.content)
            assertEquals("Movie", item["title"]?.jsonPrimitive?.content)
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", item["hash"]?.jsonPrimitive?.content)
        } finally { root.deleteRecursively() }
    }
    @Test fun commonJsWatchExportsReceiveTheRecordingAndCannotAccessJavaOrFiles() = runBlocking {
        val root = Files.createTempDirectory("watch-js").toFile()
        try {
            val script = """
                module.exports.getStreams = async function(id, type, season, episode, context) {
                    var escaped = false;
                    try { Java.type('java.lang.Runtime'); escaped = true; } catch(e) {}
                    if (typeof file !== 'undefined') escaped = true;
                    return [{url:'https://example.com/' + id + '/' + season + '/' + episode + '.mp4', name:context.title, escaped:escaped, type:type}];
                };
            """.trimIndent()
            val answer = WatchScriptRuntime.streams(script, buildJsonObject {
                put("tmdbId", "1399"); put("mediaType", "tv"); put("season", 2); put("episode", 3); put("title", "A series")
            }, listOf("example.com"), root).jsonArray.single().jsonObject
            assertEquals("https://example.com/1399/2/3.mp4", answer["url"]?.jsonPrimitive?.content)
            assertEquals(false, answer["escaped"]?.jsonPrimitive?.boolean)
            assertEquals("A series", answer["name"]?.jsonPrimitive?.content)
        } finally { root.deleteRecursively() }
    }
}
