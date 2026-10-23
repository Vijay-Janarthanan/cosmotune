package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.model.Song
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RecommendationModelTest {
    @Test
    fun correctCreditAndDurationGateSeedMatchesAndAlternateVersionsStayOut() {
        val file = Files.createTempFile("recommendation-test-", ".ctrec").toFile()
        try {
            file.writeBytes(fixture())
            val model = RecommendationModel.open(file)
            val seed = Song("provider-seed", "Seed (From \"Film\")", "Singer", null, durationText = "3:00")
            assertEquals(listOf("Neighbour"), model.recommend(seed).map { it.title })
            assertTrue(model.recommend(seed.copy(artist = "Someone Else")).isEmpty())
            assertTrue(model.recommend(seed.copy(durationText = "9:00")).isEmpty())
            assertTrue(model.recommend(seed.copy(title = "Other Song")).isEmpty())
        } finally { file.delete() }
    }

    @Test
    fun indicMarksAndSupplementaryLettersRetainIdentity() {
        assertNotEquals(RecommendationModel.normalize("కల"), RecommendationModel.normalize("కాల"))
        assertNotEquals(RecommendationModel.normalize("கலை"), RecommendationModel.normalize("கலி"))
        assertEquals("𠀀", RecommendationModel.normalize("𠀀"))
        assertEquals("song", RecommendationModel.titleKey("Song (From \"Film\")"))
        assertEquals("song live", RecommendationModel.titleKey("Song (Live)"))
    }

    @Test
    fun malformedLayoutAndUnsortedIndexesAreRejected() {
        val file = Files.createTempFile("recommendation-invalid-", ".ctrec").toFile()
        try {
            file.writeBytes(fixture().copyOf(41))
            assertFailsWith<IllegalArgumentException> { RecommendationModel.open(file) }
            val bytes = fixture()
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(12, Int.MAX_VALUE)
            file.writeBytes(bytes)
            assertFailsWith<IllegalArgumentException> { RecommendationModel.open(file) }
            val unsorted = fixture()
            ByteBuffer.wrap(unsorted).order(ByteOrder.LITTLE_ENDIAN).putLong(40 + 56 + 32, Long.MIN_VALUE)
            file.writeBytes(unsorted)
            assertFailsWith<IllegalArgumentException> { RecommendationModel.open(file) }
        } finally { file.delete() }
    }

    private fun fixture(): ByteArray {
        val names = listOf("Seed", "Neighbour", "Neighbour Remix")
        fun hash(title: String) = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(RecommendationModel.titleKey(title).toByteArray())).long
        val order = names.indices.sortedBy { hash(names[it]) }
        val index = order.withIndex().associate { it.value to it.index }
        val strings = java.io.ByteArrayOutputStream().apply { write(0) }
        fun text(value: String): Int = strings.size().also { strings.write(value.toByteArray()); strings.write(0) }
        val records = ByteBuffer.allocate(56 * names.size).order(ByteOrder.LITTLE_ENDIAN)
        for (id in order) {
            records.putInt(text("source:$id")).putInt(text(names[id])).putInt(text("Singer")).putInt(text("Album"))
            records.putInt(180).putFloat(1f).putInt(1).putInt(if (id == 2) 1 else 0)
            records.putLong(hash(names[id])).putLong(0).putLong(0)
        }
        val offsets = ByteBuffer.allocate(4 * (names.size + 1)).order(ByteOrder.LITTLE_ENDIAN)
        var count = 0
        for (id in order) { offsets.putInt(count); if (id == 0) count += 2 }
        offsets.putInt(count)
        val edges = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        for (id in listOf(1, 2)) edges.putInt(index.getValue(id)).putFloat(.8f).putShort(5).put(1).put(0)
        val head = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        head.put("CTREC003".toByteArray()).putInt(3).putInt(3).putInt(2).putInt(strings.size()).putInt(56).putInt(12).putInt(2).putInt(0)
        return head.array() + records.array() + offsets.array() + edges.array() + strings.toByteArray()
    }
}
