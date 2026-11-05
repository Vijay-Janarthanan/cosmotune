package com.cosmotune.providers

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*

/** Bounded SDK cipher utilities use platform cryptography; they cannot open app credential stores. */
internal object ProviderCrypto {
    const val MAX_BYTES = 256 * 1024
    fun call(action: String, args: JsonArray, checkCancelled: () -> Unit): JsonObject = try {
        checkCancelled()
        val output = if (action in setOf("encrypt", "decrypt")) text(action == "decrypt", args) else block(action, args, checkCancelled)
        checkCancelled()
        output
    } catch (_: Exception) {
        buildJsonObject { put("success", false); put("error", "Cipher parameters, payload or authentication are invalid") }
    }

    private fun text(decrypt: Boolean, args: JsonArray): JsonObject {
        require(args.size == 2 && args[1] is JsonPrimitive && args[1] != JsonNull)
        val keyText = (args.getOrNull(1) as? JsonPrimitive)?.content.orEmpty()
        require(keyText.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val key = MessageDigest.getInstance("SHA-256").digest(keyText.toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val output = if (decrypt) {
            val input = bytes(args.getOrNull(0), "base64")
            require(input.size >= 28)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, input.copyOfRange(0, 12)))
            JsonPrimitive(cipher.doFinal(input, 12, input.size - 12).toString(Charsets.UTF_8))
        } else {
            val input = bytes(args.getOrNull(0), "utf8")
            require(input.size <= MAX_BYTES - 28)
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            JsonPrimitive(Base64.getEncoder().encodeToString(iv + cipher.doFinal(input)))
        }
        return buildJsonObject { put("success", true); put("data", output) }
    }

    private fun block(action: String, args: JsonArray, checkCancelled: () -> Unit): JsonObject {
        require(action in setOf("encryptBlockCipher", "decryptBlockCipher", "decryptCTRSegments"))
        val options = args.getOrNull(1) as? JsonObject ?: JsonObject(emptyMap())
        fun option(key: String, default: String): String = (options[key] as? JsonPrimitive)?.contentOrNull ?: default
        val input = bytes(args.getOrNull(0), option("inputEncoding", "base64"))
        if (action == "decryptCTRSegments") {
            val segments = options["segments"] as? JsonArray ?: error("Missing cipher segments")
            require(segments.size <= 4_096)
            val output = input.copyOf()
            var processed = 0
            var processedBytes = 0L
            for (raw in segments) {
                checkCancelled()
                val segment = raw as? JsonObject ?: error("Invalid cipher segment")
                val offset = (segment["offset"] as? JsonPrimitive)?.intOrNull ?: error("Invalid segment offset")
                val length = (segment["size"] as? JsonPrimitive)?.intOrNull ?: error("Invalid segment length")
                require(offset >= 0 && length >= 0 && offset <= output.size - length)
                processedBytes += length
                require(processedBytes <= MAX_BYTES * 4L)
                if (length == 0) continue
                val size = if (option("algorithm", "aes").lowercase() == "blowfish") 8 else 16
                val iv = bytes(segment["iv"], option("ivEncoding", "base64")); require(iv.size <= size)
                val settings = JsonObject(options + mapOf("algorithm" to JsonPrimitive(option("algorithm", "aes")), "mode" to JsonPrimitive("ctr"),
                    "keyEncoding" to JsonPrimitive(option("keyEncoding", "hex"))))
                transform(output.copyOfRange(offset, offset + length), settings, true, iv.copyOf(size)).copyInto(output, offset)
                processed++
            }
            return buildJsonObject { put("success", true); put("data", encoded(output, option("outputEncoding", "base64"))); put("segments_processed", processed) }
        }
        val output = transform(input, options, action == "decryptBlockCipher")
        return buildJsonObject {
            put("success", true); put("data", encoded(output, option("outputEncoding", "base64")))
            put("block_size", if (option("algorithm", "").lowercase() == "blowfish") 8 else 16)
        }
    }

    private fun transform(input: ByteArray, options: JsonObject, decrypt: Boolean, suppliedIv: ByteArray? = null): ByteArray {
        return cipher(options, decrypt, suppliedIv).doFinal(input).also { require(it.size <= MAX_BYTES + 32) }
    }

    internal fun cipher(options: JsonObject, decrypt: Boolean, suppliedIv: ByteArray? = null): Cipher {
        fun option(key: String, default: String) = (options[key] as? JsonPrimitive)?.contentOrNull ?: default
        val algorithm = option("algorithm", "").lowercase()
        val mode = option("mode", "cbc").lowercase()
        require(mode in setOf("cbc", "ctr"))
        val key = bytes(options["key"], option("keyEncoding", "utf8"))
        val cipherName = when {
            algorithm == "aes" || algorithm in setOf("aes-128", "aes-192", "aes-256") -> {
                require(key.size in setOf(16, 24, 32) && (algorithm == "aes" || algorithm.substringAfter('-').toInt() == key.size * 8)); "AES"
            }
            algorithm == "blowfish" -> { require(key.size in 4..56); "Blowfish" }
            else -> error("Unsupported cipher")
        }
        val blockSize = if (cipherName == "AES") 16 else 8
        val iv = suppliedIv ?: bytes(options["iv"], option("ivEncoding", "utf8"))
        require(iv.size == blockSize)
        val padding = if (mode == "cbc" && option("padding", "none") == "pkcs7") "PKCS5Padding" else "NoPadding"
        val cipher = Cipher.getInstance("$cipherName/${mode.uppercase()}/$padding")
        cipher.init(if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE, SecretKeySpec(key, cipherName), IvParameterSpec(iv))
        return cipher
    }

    private fun bytes(value: JsonElement?, encoding: String): ByteArray {
        val array = (if (value is JsonObject) value["__bytes"] else value) as? JsonArray
        if (array != null) {
            require(array.size <= MAX_BYTES)
            return ByteArray(array.size) { (array[it] as? JsonPrimitive)?.intOrNull?.also { v -> require(v in 0..255) }?.toByte() ?: error("Invalid binary value") }
        }
        val text = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
        require(text.length <= MAX_BYTES * 2)
        return when (encoding.lowercase()) {
            "base64", "base64url" -> Base64.getDecoder().decode(text.filterNot(Char::isWhitespace).replace('-', '+').replace('_', '/'))
            "hex" -> { require(text.length % 2 == 0 && text.matches(Regex("[a-fA-F0-9]*"))); ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
            "utf8", "utf-8" -> text.toByteArray(Charsets.UTF_8)
            "binary", "latin1", "raw", "bytes" -> ByteArray(text.length) { text[it].code.toByte() }
            else -> error("Unsupported binary encoding")
        }.also { require(it.size <= MAX_BYTES) }
    }
    private fun encoded(bytes: ByteArray, encoding: String): JsonElement = when (encoding.lowercase()) {
        "bytes", "raw" -> buildJsonObject { putJsonArray("__bytes") { bytes.forEach { add(it.toInt() and 255) } }; put("__buffer", true) }
        "base64" -> JsonPrimitive(Base64.getEncoder().encodeToString(bytes))
        "base64url" -> JsonPrimitive(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        "hex" -> JsonPrimitive(bytes.joinToString("") { "%02x".format(it) })
        "utf8", "utf-8" -> JsonPrimitive(bytes.toString(Charsets.UTF_8))
        "binary", "latin1" -> JsonPrimitive(bytes.toString(Charsets.ISO_8859_1))
        else -> error("Unsupported output encoding")
    }
}
