package com.cosmotune.video.source.newpipe.fixtures

import com.cosmotune.video.model.SearchFilter
import com.cosmotune.video.source.newpipe.NewPipeHost
import com.cosmotune.video.source.newpipe.NewPipeVideoSource
import com.cosmotune.video.source.newpipe.VideoDownloader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.File

/**
 * Removes what must not be committed from a recorded answer: the values that tie it to a connection or a visitor, and any
 * cookie the server set (plan task Y-4, step 6). Fixed placeholders replace values; `Set-Cookie` headers are dropped.
 *
 * Scrubbed: the `ip`, `ei`, `sig`, `lsig`, `n` and `pot` parameters of an address (`ip` wherever `ip=` is followed by a
 * digit), `VISITOR_INFO1_LIVE=` values, and `visitorData` / `visitor_data` fields; kept of the headers: `Content-Type`.
 *
 * An address inside a JSON answer separates its parameters with an escaped ampersand (backslash, `u0026`), so `u0026` counts as a
 * separator like `?`, `&` and `;`.
 */
object FixtureScrubber {
    const val PLACEHOLDER = "REDACTED"

    private val addressParameter = Regex("""((?:[?&;]|u0026)(?:ei|sig|lsig|n|pot)=)[^&"\\\s]*""")
    private val ipValue = Regex("""ip=[0-9][^&"\\\s]*""")
    private val visitorCookie = Regex("""(VISITOR_INFO1_LIVE=)[^;"\\\s]*""")
    private val visitorField = Regex(""""(visitorData|visitor_data|visitorId)"\s*:\s*"[^"]*"""")

    /** The country the source guessed from the connection (`"gl":"XX"` in the client's configuration): the default region, not the recorder's. */
    private val country = Regex(""""gl"\s*:\s*"[A-Z]{2}"""")

    /** The form `{"key":"visitor_data","value":"..."}` of the tracking parameters of a response's context. */
    private val visitorParameter = Regex(""""key"\s*:\s*"visitor_data"\s*,\s*"value"\s*:\s*"[^"]*"""")

    /** [text] with every value above replaced; running it twice changes nothing more. */
    fun scrub(text: String): String = text
        .replace(ipValue, "ip=$PLACEHOLDER")
        .replace(addressParameter) { "${it.groupValues[1]}$PLACEHOLDER" }
        .replace(visitorCookie) { "${it.groupValues[1]}$PLACEHOLDER" }
        .replace(visitorField) { "\"${it.groupValues[1]}\":\"$PLACEHOLDER\"" }
        .replace(visitorParameter) { "\"key\":\"visitor_data\",\"value\":\"$PLACEHOLDER\"" }
        .replace(country, "\"gl\":\"GB\"")

    /**
     * What is left in [text] that the scrubber should have removed (empty when the text is clean). [text] may be a body or the
     * file around it, in which the body's quotes are escaped, so a backslash may stand before a quote.
     */
    fun problems(text: String): List<String> = buildList {
        if (Regex("""ip=[0-9]""").containsMatchIn(text)) add("ip= followed by a digit")
        if (Regex("set-cookie", RegexOption.IGNORE_CASE).containsMatchIn(text)) add("Set-Cookie")
        if (Regex("""VISITOR_INFO1_LIVE=(?!$PLACEHOLDER)[^;"\\\s]+""").containsMatchIn(text)) add("a VISITOR_INFO1_LIVE value")
        if (Regex("""(?:[?&;]|u0026)(?:ei|sig|lsig|n|pot)=(?!$PLACEHOLDER)[^&"\\\s]+""").containsMatchIn(text)) add("an ei, sig, lsig, n or pot value")
        if (Regex("""\\?"(?:visitorData|visitor_data|visitorId)\\?"\s*:\s*\\?"(?!$PLACEHOLDER)[^"\\]+""").containsMatchIn(text)) add("a visitorData value")
        if (Regex("""\\?"key\\?"\s*:\s*\\?"visitor_data\\?"\s*,\s*\\?"value\\?"\s*:\s*\\?"(?!$PLACEHOLDER)[^"\\]+""").containsMatchIn(text)) add("a visitor_data parameter value")
        if (Regex("""\\?"gl\\?"\s*:\s*\\?"(?!GB)[A-Z]{2}""").containsMatchIn(text)) add("a country other than the default")
    }

    /** The headers worth keeping of an answer: its content type. */
    fun keptHeaders(headers: Map<String, List<String>>): Map<String, List<String>> =
        headers.filterKeys { it.equals("content-type", ignoreCase = true) }
}

/**
 * Makes a recorded JSON answer smaller by leaving out what the extractor never reads and what is only bulk: a search page is
 * half a megabyte to three megabytes, most of it the video summaries, the menus of every card, the autoplay endpoints and the
 * tracking tokens. Only objects and arrays are rebuilt; every value that stays is kept as it was. An answer that is not JSON (the
 * script the client version is read from) is left alone. `NewPipeMapper` sees the same results from a slimmed answer as from
 * a whole one (checked once, when this list was made, by comparing every field of the mapped search results).
 */
object FixtureSlimmer {
    private val dropped = setOf(
        "expandableMetadata", "menu", "menuOnTap", "trackingParams", "clickTrackingParams", "loggingDirectives", "frameworkUpdates",
        "searchVideoResultEntityKey", "inlinePlaybackEndpoint", "thumbnailOverlayToggleButtonRenderer", "sequenceParams",
    )

    /** [body] without the dropped keys (and the long `params` of every `watchEndpoint`), or [body] itself when it is not JSON. */
    fun slim(body: String): String {
        val parsed = try {
            Json.parseToJsonElement(body)
        } catch (e: Exception) {
            return body
        }
        return Json.encodeToString(JsonElement.serializer(), prune(parsed, null))
    }

    private fun prune(node: JsonElement, parent: String?): JsonElement = when (node) {
        is JsonObject -> JsonObject(
            node.filterKeys { it !in dropped && !(it == "params" && parent == "watchEndpoint") }.mapValues { (key, value) -> prune(value, key) },
        )
        is JsonArray -> JsonArray(node.map { prune(it, parent) })
        else -> node
    }
}

/** A downloader that asks another and writes what it was asked and what came back, scrubbed, as numbered files of a scenario. */
class RecordingDownloader(private val real: Downloader, private val directory: File, private val scenario: String) : Downloader() {
    private var count = 0

    @Synchronized
    override fun execute(request: Request): Response {
        val response = real.execute(request)
        count++
        val json = JsonObject(
            mapOf(
                "method" to JsonPrimitive(request.httpMethod()),
                "url" to JsonPrimitive(FixtureScrubber.scrub(request.url())),
                "status" to JsonPrimitive(response.responseCode()),
                "headers" to JsonObject(
                    FixtureScrubber.keptHeaders(response.responseHeaders()).mapValues { (_, v) -> JsonArray(v.map { JsonPrimitive(it) }) },
                ),
                "body" to JsonPrimitive(FixtureSlimmer.slim(FixtureScrubber.scrub(response.responseBody().orEmpty()))),
            ),
        )
        val text = pretty.encodeToString(JsonObject.serializer(), json)
        val problems = FixtureScrubber.problems(text)
        check(problems.isEmpty()) { "The recording of ${request.httpMethod()} ${request.url()} still holds: $problems" }
        directory.mkdirs()
        val name = "${scenario}_${count.toString().padStart(2, '0')}"
        File(directory, "$name.json").writeText(text + "\n", Charsets.UTF_8)
        println("recorded $name: ${request.httpMethod()} ${request.url().substringBefore('?')} -> ${response.responseCode()} (${text.length} chars)")
        return response
    }

    private companion object {
        val pretty = Json { prettyPrint = true }
    }
}

/**
 * A manual tool, not a test (plan task Y-4, step 6): with the network, it runs the searches the replay tests use through the
 * real adapter and a recording downloader, and writes the scrubbed answers to `shared/src/jvmTest/resources/video/newpipe/`.
 *
 * Scenarios: `search_all` (a search for "kurzgesagt" with the filter `all`: its first page), `search_videos` (the same with the
 * filter `videos`) and `search_shorts` (one Shorts search for "#shorts", which reads the `all` search page by page until it has
 * ten Shorts: two or three pages, so the same files also serve as an `all` search with its continuation pages).
 *
 * Run from the repository root with the test classpath of `:shared:jvmTest`:
 * `java -cp <classpath> com.cosmotune.video.source.newpipe.fixtures.RecorderKt [output directory]`.
 * With `--only=<scenario>` only that scenario is recorded again. With `--rescrub` it makes no request: it applies the scrubber and the slimmer again to the files already written (for a rule added later).
 */
fun main(args: Array<String>) {
    val directory = File(args.firstOrNull { !it.startsWith("--") } ?: "shared/src/jvmTest/resources/${ReplayDownloader.RESOURCE_DIRECTORY}")
    if ("--rescrub" in args) {
        rescrub(directory)
        return
    }

    /** Records [scenario] afresh (its old files are deleted first); [steps] says whether the recording is the one wanted. */
    fun record(scenario: String, steps: suspend (NewPipeVideoSource) -> Boolean): Boolean {
        directory.listFiles { f -> f.name.startsWith("${scenario}_") && f.extension == "json" }?.forEach { it.delete() }
        NewPipeHost.resetForTests()
        YoutubeParsingHelper.resetClientVersion()
        NewPipeHost.videoDownloader = RecordingDownloader(VideoDownloader(), directory, scenario)
        return runBlocking { steps(NewPipeVideoSource()) }
    }

    val only = args.firstOrNull { it.startsWith("--only=") }?.removePrefix("--only=")

    if (only == null || only == "search_all") {
        record("search_all") { source -> source.search("kurzgesagt", SearchFilter.ALL).items.isNotEmpty() }
    }
    if (only == null || only == "search_videos") {
        record("search_videos") { source -> source.search("kurzgesagt", SearchFilter.VIDEOS).items.isNotEmpty() }
    }
    if (only == null || only == "search_shorts") {
        // The source offers different pages from one request to the next, and a Shorts search needs pages that hold Shorts:
        // record again until the search finds the ten it is asked for.
        var attempts = 0
        while (!record("search_shorts") { source -> source.search("#shorts", SearchFilter.SHORTS).items.size >= 10 }) {
            check(++attempts < MAX_SHORTS_ATTEMPTS) { "No search for #shorts found ten Shorts in $MAX_SHORTS_ATTEMPTS tries: record another query" }
        }
    }
}

private const val MAX_SHORTS_ATTEMPTS = 6

/** Applies [FixtureScrubber] and [FixtureSlimmer] again to every recording in [directory] and writes it back; fails if anything is still left. */
fun rescrub(directory: File) {
    val pretty = Json { prettyPrint = true }
    val files = directory.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
    check(files.isNotEmpty()) { "No recordings in $directory" }
    for (file in files) {
        val json = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as JsonObject
        val scrubbed = JsonObject(
            json.mapValues { (key, value) ->
                if (key == "url" && value is JsonPrimitive) JsonPrimitive(FixtureScrubber.scrub(value.content))
                else if (key == "body" && value is JsonPrimitive) JsonPrimitive(FixtureSlimmer.slim(FixtureScrubber.scrub(value.content)))
                else value
            },
        )
        val text = pretty.encodeToString(JsonObject.serializer(), scrubbed)
        val problems = FixtureScrubber.problems(text)
        check(problems.isEmpty()) { "${file.name} still holds: $problems" }
        file.writeText(text + "\n", Charsets.UTF_8)
        println("rescrubbed ${file.name} (${text.length} chars)")
    }
}
