package com.cosmotune.music.data.canvas

import java.nio.file.Files
import kotlin.test.*

class ArtistArtDiskCacheTest {
    private val directory = Files.createTempDirectory("artist-art-test").toFile()
    private val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
    private val art = AppleArtistArt("https://is1-ssl.mzstatic.com/hero.jpg", "https://is1-ssl.mzstatic.com/logo.png",
        3f, "https://video.music.apple.com/loop.m3u8", 1, 2, 3)
    @AfterTest fun cleanup() { directory.deleteRecursively() }

    @Test fun portraitAndLogoSurviveAProcessRestartWithoutNetwork() {
        var calls = 0
        val cache = ArtistArtDiskCache(directory, fetch = { calls++; png }, now = { 1000L })
        val saved = assertNotNull(cache.save("artist", art)).art!!
        assertTrue(saved.heroUrl.startsWith("file:"))
        assertTrue(saved.logoUrl!!.startsWith("file:"))
        assertEquals(2, calls)
        val restored = assertNotNull(ArtistArtDiskCache(directory, fetch = { error("no requests on read") }, now = { 2000L }).read("artist"))
        assertEquals(saved, restored.art)
        assertTrue(restored.fresh(2000L))
        assertNull(ArtistArtDiskCache(directory, now = { 31 * 24 * 60 * 60_000L }).read("artist"))
    }

    @Test fun failedImageRefreshRetainsThePreviousUsablePortraitAndLogo() {
        val first = ArtistArtDiskCache(directory, fetch = { png }, now = { 1000L }).save("artist", art)!!.art!!
        val refreshed = ArtistArtDiskCache(directory, fetch = { throw java.io.IOException("offline") }, now = { 2000L })
            .save("artist", art.copy(heroUrl = "https://is1-ssl.mzstatic.com/new.jpg", logoUrl = "https://is1-ssl.mzstatic.com/new.png"))!!.art!!
        assertEquals(first.heroUrl, refreshed.heroUrl)
        assertEquals(first.logoUrl, refreshed.logoUrl)
    }

    @Test fun arbitraryHostsAndDisguisedAppleDomainsAreNotDownloaded() {
        var calls = 0
        val cache = ArtistArtDiskCache(directory, fetch = { calls++; png }, now = { 1000L })
        cache.save("artist", art.copy(heroUrl = "https://mzstatic.com.attacker.example/hero.jpg", logoUrl = "https://example.com/logo.png"))
        assertEquals(0, calls)
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "img" })
    }

    @Test fun expiredMotionLinksAreDroppedWhileStillArtRemainsAvailable() {
        ArtistArtDiskCache(directory, fetch = { png }, now = { 1000L }).save("artist", art)
        val restored = ArtistArtDiskCache(directory, now = { 2 * 60 * 60_000L }).read("artist")!!.art!!
        assertTrue(restored.heroUrl.startsWith("file:"))
        assertNull(restored.videoUrl)
    }
}
