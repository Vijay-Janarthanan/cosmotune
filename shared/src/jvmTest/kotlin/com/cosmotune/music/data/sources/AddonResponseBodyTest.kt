package com.cosmotune.music.data.sources

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AddonResponseBodyTest {
    @Test fun readsUnicodeMetadataWithoutChangingIt() {
        val json = """{"title":"మానసా — தமிழ்"}"""
        json.toResponseBody("application/json; charset=utf-8".toMediaType()).use {
            assertEquals(json, it.readAddonJsonBody())
        }
    }

    @Test fun rejectsUnknownLengthBodiesWhenTheirActualBytesExceedTheLimit() {
        val bytes = Buffer().write(ByteArray((MAX_ADDON_JSON_BYTES + 1).toInt()) { 32 })
        val body = object : ResponseBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = -1L
            override fun source(): BufferedSource = bytes
        }
        body.use { assertFailsWith<IOException> { it.readAddonJsonBody() } }
    }

    @Test fun permitsExactlyTheLimitAndRejectsLargerDeclaredBodies() {
        val bytes = " ".repeat(MAX_ADDON_JSON_BYTES.toInt())
        bytes.toResponseBody().use { assertEquals(bytes.length, it.readAddonJsonBody().length) }
        (bytes + " ").toResponseBody().use { assertFailsWith<IOException> { it.readAddonJsonBody() } }
    }
}
