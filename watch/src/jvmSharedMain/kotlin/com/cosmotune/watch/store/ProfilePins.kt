package com.cosmotune.watch.store

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Device-local convenience lock. The hash is never copied into cloud profile metadata. */
internal object ProfilePins {
    fun encode(pin: String): String {
        require(pin.matches(Regex("[0-9]{4,8}"))) { "Use four to eight digits" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        return "v1:${Base64.getEncoder().encodeToString(salt)}:${Base64.getEncoder().encodeToString(hash(pin, salt))}"
    }
    fun verify(pin: String?, encoded: String?): Boolean {
        if (encoded == null) return true
        if (pin == null || !pin.matches(Regex("[0-9]{4,8}"))) return false
        return runCatching {
            val parts = encoded.split(':')
            require(parts.size == 3 && parts[0] == "v1")
            val salt = Base64.getDecoder().decode(parts[1]); val expected = Base64.getDecoder().decode(parts[2])
            require(salt.size == 16 && expected.size == 32)
            MessageDigest.isEqual(hash(pin, salt), expected)
        }.getOrDefault(false)
    }
    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 100_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
}
