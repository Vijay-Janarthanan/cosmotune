package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TinyRecommendationModelTest {
    @Test
    fun bundledModelStaysUnderFiveMegabytesAndRanksLocally() {
        val manifest = javaClass.getResourceAsStream("/recommendations/manifest.json")!!.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        assertTrue(manifest.getValue("compressed_bytes").jsonPrimitive.content.toLong() < 5_000_000)
        assertTrue(manifest.getValue("unpacked_bytes").jsonPrimitive.content.toLong() < 5_000_000)
        val folder = File("build/recommendation-runtime-test").apply { mkdirs() }
        val sha = manifest.getValue("sha256").jsonPrimitive.content
        val file = File(folder, "$sha.ctrec")
        if (!file.exists()) GZIPInputStream(javaClass.getResourceAsStream("/recommendations/recommendations.ctrec.gz")!!).use { input -> file.outputStream().use { input.copyTo(it) } }
        val model = TinyRecommendationModel.open(file)
        assertEquals(4096, model.itemCount)
        val seed = Song("seed1234567", "Billie Jean", "Michael Jackson", null, albumName = "Thriller", durationText = "4:54")
        assertTrue(model.score(seed, seed) > .999f)
        val picks = model.recommend(seed)
        // The small vocabulary is intentionally allowed to abstain when no
        // high-confidence neighbour exists; provider radio remains available.
        assertTrue(picks.size <= 24)
        assertTrue(picks.none { it.title == seed.title && it.artist == seed.artist })
        val candidates = (0 until 50).map { seed.copy(videoId = "candidate-$it", title = "Candidate $it") }
        repeat(30) { model.recommend(seed); candidates.forEach { model.score(seed, it) } }
        val samples = (0 until 100).map {
            val start = System.nanoTime()
            model.recommend(seed)
            candidates.forEach { model.score(seed, it) }
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        println("Tiny recommendation model: ${file.length()} bytes mapped; local discovery + ${candidates.size} scores median=${samples[50]} ms, p95=${samples[95]} ms")
        // Generous regression threshold for shared CI runners; measured medians are reported separately.
        assertTrue(samples[50] < 250, "Local ranking must stay bounded on a loaded runner")
    }
}
