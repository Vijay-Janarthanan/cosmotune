package com.cosmotune.music.data.innertube
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
class YouTubeRequestPolicyTest {
    @Test fun drcCannotOutrankOriginalAudioOfTheSameLanguage() {
        val root=Json.parseToJsonElement("""{"streamingData":{"adaptiveFormats":[{"itag":251,"mimeType":"audio/webm","isDrc":true,"audioTrack":{"id":"en"}},{"itag":140,"mimeType":"audio/mp4","audioTrack":{"id":"en"}},{"itag":252,"mimeType":"audio/webm","isDrc":true,"audioTrack":{"id":"fr"}}]}}""").jsonObject
        val formats=YouTubeRequestPolicy.preferOriginalAudio(root)["streamingData"]!!.jsonObject["adaptiveFormats"]!!.jsonArray
        assertEquals(listOf(140,252),formats.map { it.jsonObject["itag"]!!.jsonPrimitive.int })
        assertTrue(YouTubeRequestPolicy.isDrcUrl("https://media.example/audio?xtags=foo%3Abar%3Adrc%3D1"))
        assertFalse(YouTubeRequestPolicy.isDrcUrl("https://media.example/audio?xtags=drc%3D10"))
    }
    @Test fun drcOnlyDoesNotBorrowOriginalNormalizationMetadata() {
        val root=Json.parseToJsonElement("""{"playerConfig":{"audioConfig":{"loudnessDb":8.0}},"streamingData":{"adaptiveFormats":[{"mimeType":"audio/mp4","isDrc":true}]}}""").jsonObject
        val fixed=YouTubeRequestPolicy.preferOriginalAudio(root)
        assertNull(fixed["playerConfig"]!!.jsonObject["audioConfig"]!!.jsonObject["loudnessDb"])
        assertEquals(1,fixed["streamingData"]!!.jsonObject["adaptiveFormats"]!!.jsonArray.size)
    }
    @Test fun rejectedLocaleRetriesAreBoundedAndLeaveExplicitDiscoveryChoicesUntouched() {
        val requests=CopyOnWriteArrayList<JsonObject>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/youtubei/v1/browse") { exchange ->
            requests+=Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val body=when(requests.size) {
                1 -> """{"error":{"code":400,"message":"Unsupported language code"}}"""
                2 -> """{"error":{"code":400,"message":"Unsupported country code"}}"""
                else -> """{"contents":{}}"""
            }.toByteArray()
            exchange.sendResponseHeaders(if(requests.size<3)400 else 200,body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val body="""{"context":{"client":{"hl":"sat","gl":"CN"}},"browseId":"FEmusic_home"}"""
            val original=Request.Builder().url("http://127.0.0.1:"+server.address.port+"/youtubei/v1/browse?hl=sat")
                .post(body.toRequestBody("application/json".toMediaType())).build()
            OkHttpClient.Builder().addInterceptor(YouTubeRequestPolicy).build().newCall(original).execute().use { assertEquals(200,it.code) }
            assertEquals(3,requests.size)
            fun client(i:Int)=requests[i]["context"]!!.jsonObject["client"]!!.jsonObject
            assertEquals("sat",client(0)["hl"]!!.jsonPrimitive.content)
            assertNull(client(1)["hl"]); assertEquals("CN",client(1)["gl"]!!.jsonPrimitive.content)
            assertNull(client(2)["hl"]); assertNull(client(2)["gl"])
            assertEquals("sat",original.url.queryParameter("hl"))
        } finally { server.stop(0) }
    }
    @Test fun genericAuthenticationAndTransientErrorsAreNotLocaleFailures() {
        for(message in listOf("Sign in required","Service unavailable","Invalid argument"))
            assertNull(YouTubeRequestPolicy.rejectedLocaleField(buildJsonObject { putJsonObject("error") { put("message",message) } }))
    }
}
