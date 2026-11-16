package com.cosmotune.music.data

import com.cosmotune.music.data.model.LikeStatus
import java.io.File
import kotlin.test.*

class LocalFileRatingsTest {
    @Test fun reindexedMediaRowsKeepFavoritesWithoutNameOrMetadataGuessing() {
        val directory = kotlin.io.path.createTempDirectory("local-ratings-").toFile()
        try {
            val file = File(directory, "ratings.json")
            val first = LocalFileRatings.identity("/storage/Music/Artist/song.flac", "content://media/audio/12")
            val reindexed = LocalFileRatings.identity("/storage/Music/Artist/song.flac", "content://media/audio/999")
            LocalFileRatings(file).set(first, LikeStatus.LIKE)
            assertEquals(first, reindexed)
            assertEquals(LikeStatus.LIKE, LocalFileRatings(file).read()[reindexed])
            assertNotEquals(first, LocalFileRatings.identity("/storage/Music/Other/song.flac", "content://media/audio/12"))
            LocalFileRatings(file).set(first, LikeStatus.INDIFFERENT)
            assertEquals(LikeStatus.INDIFFERENT, LocalFileRatings(file).read()[reindexed])
            assertFalse(com.cosmotune.cloud.SyncLikes.travels(first))
            assertFalse(com.cosmotune.cloud.SyncLikes.travels("content://media/audio/999"))
        } finally { directory.listFiles().orEmpty().forEach(File::delete); directory.delete() }
    }

    @Test fun damagedRatingsArePreservedInsteadOfReplacedByANewLike() {
        val file = kotlin.io.path.createTempFile("local-ratings-", ".json").toFile()
        try {
            file.writeText("{damaged")
            assertFails { LocalFileRatings(file).set("local:/Music/song.flac", LikeStatus.LIKE) }
            assertEquals("{damaged", file.readText())
        } finally { file.delete() }
    }
}
