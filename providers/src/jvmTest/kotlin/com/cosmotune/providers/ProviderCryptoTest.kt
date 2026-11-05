package com.cosmotune.providers

import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderCryptoTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = "2b7e151628aed2a6abf7158809cf4f3c"
    private val plain = "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51"
    private fun options(mode: String) = buildJsonObject {
        put("algorithm", "aes-128"); put("mode", mode); put("key", key)
        put("iv", if (mode == "ctr") "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff" else "000102030405060708090a0b0c0d0e0f")
        put("keyEncoding", "hex"); put("ivEncoding", "hex"); put("inputEncoding", "hex"); put("outputEncoding", "hex")
    }
    private fun call(action: String, value: String, options: JsonObject): JsonObject = ProviderCrypto.call(action, buildJsonArray { add(value); add(options) }) {}
    private fun success(result: JsonObject): String { assertEquals(JsonPrimitive(true), result["success"], result.toString()); return result.getValue("data").jsonPrimitive.content }

    // NIST SP 800-38A F.2.1 and F.5.1: https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-38a.pdf
    @Test fun platformAesCbcAndCtrMatchThePublishedMultiBlockVectors() {
        for ((mode, ciphertext) in listOf("cbc" to "7649abac8119b246cee98e9b12e9197d5086cb9b507219ee95db113a917678b2",
            "ctr" to "874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff")) {
            assertEquals(ciphertext, success(call("encryptBlockCipher", plain, options(mode))))
            assertEquals(plain, success(call("decryptBlockCipher", ciphertext, options(mode))))
        }
        val padding = JsonObject(options("cbc") + ("padding" to JsonPrimitive("pkcs7")))
        val ciphertext = success(call("encryptBlockCipher", "010203", padding))
        assertEquals("010203", success(call("decryptBlockCipher", ciphertext, padding)))
        assertEquals(JsonPrimitive(false), call("decryptBlockCipher", ciphertext.dropLast(2), padding)["success"])
    }

    @Test fun authenticatedTextReadsTheNodeSdkWireFormatAndRejectsTampering() {
        fun text(action: String, value: String, key: String = "fixture-key") = ProviderCrypto.call(action, buildJsonArray { add(value); add(key) }) {}
        // Independently generated using Node AES-256-GCM: SHA256(password), nonce || ciphertext || 16-byte tag.
        val fromNode = "AAECAwQFBgcICQoLlzxZzrrsH4ApHq6R429YrNvyalaNUH3INMG3buz5mRDhckNKEw=="
        assertEquals("Tamil தமிழ்", success(text("decrypt", fromNode)))
        val one = success(text("encrypt", "A private value")); val two = success(text("encrypt", "A private value"))
        assertNotEquals(one, two)
        assertEquals("A private value", success(text("decrypt", one)))
        val changed = Base64.getDecoder().decode(one).apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertEquals(JsonPrimitive(false), text("decrypt", Base64.getEncoder().encodeToString(changed))["success"])
        assertEquals(JsonPrimitive(false), text("decrypt", one, "wrong-key")["success"])
    }

    @Test fun segmentCipherKeepsUnselectedBytesAndRejectsInvalidRangesAndBudgets() {
        val encrypted = "874d6191b620e3261bef6864990db6ce"
        val value = "aabb" + encrypted + "ccdd"
        val settings = buildJsonObject {
            put("algorithm", "aes"); put("key", key); put("ivEncoding", "hex"); put("inputEncoding", "hex"); put("outputEncoding", "hex")
            putJsonArray("segments") { addJsonObject { put("offset", 2); put("size", 16); put("iv", "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff") } }
        }
        val result = call("decryptCTRSegments", value, settings)
        assertEquals("aabb" + plain.take(32) + "ccdd", success(result))
        assertEquals(JsonPrimitive(1), result["segments_processed"])
        val invalid = JsonObject(settings + ("segments" to buildJsonArray { addJsonObject { put("offset", -1); put("size", 16); put("iv", "00") } }))
        assertEquals(JsonPrimitive(false), call("decryptCTRSegments", value, invalid)["success"])
        assertEquals(JsonPrimitive(false), call("encryptBlockCipher", "00".repeat(ProviderCrypto.MAX_BYTES + 1), options("ctr"))["success"])
        assertEquals(JsonPrimitive(false), call("encryptBlockCipher", plain, JsonObject(options("ctr") + ("key" to JsonPrimitive("secret-private-invalid-key"))))["success"])
    }

    @Test fun guestUtilitiesReachTheCipherHostWithoutFileOrCredentialPermissions() = runBlocking<Unit> {
        val install = ProviderInstall(ProviderManifest("fixture", "1.0.0", type = listOf("metadata_provider")), "0".repeat(64))
        val host = ProviderMetadataHost(install, ProviderStore(temporary.newFolder(), UnsealedProviderFiles))
        createProviderScriptSession("""
            registerExtension({probe:function(){
                var encrypted=utils.encrypt('தமிழ்','fixture-key');
                if(!encrypted.success)throw Error('Cipher host unavailable');
                var decrypted=utils.decrypt(encrypted.data,'fixture-key');
                if(!decrypted.success)throw Error('Cipher host unavailable');
                return decrypted.data;
            }});
        """.trimIndent(), buildJsonObject { putJsonObject("permissions") { putJsonArray("network") {}; put("storage", false); put("file", false) }; put("settings", JsonObject(emptyMap())) }, host::bridge, host.network::cancel).use { runtime ->
            assertEquals("தமிழ்", Json.parseToJsonElement(runtime.invoke("probe", JsonArray(emptyList()))).jsonPrimitive.content)
        }
    }
}
