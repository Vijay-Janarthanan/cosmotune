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

/**
 * MN-02 phone-latency budget, made deterministic: one radio ranking of 50 candidates (the three calls
 * `LocalRecommendations.enhance` makes: local discovery, batched candidate scores and the online
 * preference layer) is counted by the ranking code itself, on the bundled model, and must stay within
 * a fixed budget. The counts are the same on every machine; only their wall-clock cost differs by phone.
 */
class RankingWorkBudgetTest {
    private fun bundledModel(): TinyRecommendationModel {
        val manifest = javaClass.getResourceAsStream("/recommendations/manifest.json")!!.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val folder = File("build/recommendation-runtime-test").apply { mkdirs() }
        val file = File(folder, "${manifest.getValue("sha256").jsonPrimitive.content}.ctrec")
        if (!file.exists()) GZIPInputStream(javaClass.getResourceAsStream("/recommendations/recommendations.ctrec.gz")!!).use { input -> file.outputStream().use { input.copyTo(it) } }
        return TinyRecommendationModel.open(file)
    }

    private val seed = Song("seed1234567", "Billie Jean", "Michael Jackson", null, albumName = "Thriller", durationText = "4:54")
    private val candidates = (0 until CANDIDATES).map {
        Song("candidate-$it", "Candidate $it", "Artist ${it % 7}", null, albumName = "Album ${it % 5}", durationText = "3:${10 + it % 40}")
    }

    /** A trained layer, so the preference work is the real per-candidate path rather than the untrained short cut. */
    private fun trained(): OnlinePreferenceModel = OnlinePreferenceModel().apply {
        candidates.take(40).forEachIndexed { index, song -> learn(seed, song, liked = index % 3 != 0, hour = 20, day = DAY) }
    }

    private fun rank(model: TinyRecommendationModel, preferences: OnlinePreferenceModel, day: Long): RankingWork = RankingWork().also { work ->
        model.recommend(seed, work = work)
        model.scoreAll(seed, candidates, work)
        candidates.forEach { preferences.adjustment(seed, it, 20, day, work) }
    }

    @Test fun oneRankingOfFiftyCandidatesStaysWithinItsWorkBudget() {
        val model = bundledModel()
        val preferences = trained()
        // The first ranking on a new day pays the decay once; later ones the same day do not.
        val first = rank(model, preferences, DAY + 1)
        val later = rank(model, preferences, DAY + 1)
        println("MN-02 ranking work, $CANDIDATES candidates: first of the day {$first}; later {$later}")

        assertEquals(4_096L, first.embeddingRowsScanned, "local discovery scans the 4,096-row table exactly once")
        assertTrue(first.multiplyAdds <= MAX_MULTIPLY_ADDS, "multiply-adds ${first.multiplyAdds} > $MAX_MULTIPLY_ADDS")
        assertEquals(3 * (CANDIDATES + 2), first.sha256Digests, "three digests per encoded song: seed twice, each candidate once")
        assertTrue(first.preferenceFeatures in CANDIDATES * 5..CANDIDATES * 8, "${first.preferenceFeatures} preference features")
        assertEquals(2L * OnlinePreferenceModel.FEATURES, first.decayedValues)
        assertEquals(0L, later.decayedValues)
        assertTrue(first.sortedCandidates <= 4_096)
        // Deterministic: the same ranking does the same work.
        assertEquals(first.toString().replace(Regex("decayed=\\d+"), ""), later.toString().replace(Regex("decayed=\\d+"), ""))
    }

    @Test fun workGrowsLinearlyWithCandidatesNotWithTheCatalogue() {
        val model = bundledModel()
        val preferences = trained()
        val one = RankingWork().also { model.scoreAll(seed, candidates.take(1), it); candidates.take(1).forEach { song -> preferences.adjustment(seed, song, 20, DAY, it) } }
        val fifty = RankingWork().also { model.scoreAll(seed, candidates, it); candidates.forEach { song -> preferences.adjustment(seed, song, 20, DAY, it) } }
        // Per extra candidate: one encode (5 x 32) plus one 32-wide dot, three digests and at most eight features.
        assertEquals(49L * 6 * 32, fifty.multiplyAdds - one.multiplyAdds)
        assertEquals(49 * 3, fifty.sha256Digests - one.sha256Digests)
        assertTrue(fifty.preferenceFeatures - one.preferenceFeatures <= 49 * 8)
    }

    private companion object {
        const val CANDIDATES = 50
        const val DAY = 20_000L
        /** Discovery scan 4,096 x 32, plus (50 + 2) encodes x 5 x 32, plus 50 dots x 32 = 141,472; budget with headroom. */
        const val MAX_MULTIPLY_ADDS = 150_000L
    }
}
