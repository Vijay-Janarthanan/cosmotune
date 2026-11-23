package com.cosmotune.manga.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Manga's own PIN, for computers whose system has no sign-in prompt Manga can ask for. Only a salted PBKDF2 hash is kept
 * (`v1:salt:hash`), never the digits.
 */
object MangaLockPin {
    private const val ROUNDS = 120_000
    private val random = SecureRandom()

    /** Why [pin] cannot be used, in the listener's words, or null when it can. */
    fun problem(pin: String): String? = when {
        pin.any { !it.isDigit() } -> "Use digits only"
        pin.length < 4 -> "Use at least 4 digits"
        pin.length > 12 -> "Use at most 12 digits"
        else -> null
    }

    fun hash(pin: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return "v1:" + b64(salt) + ":" + b64(derive(pin, salt))
    }

    fun matches(pin: String, stored: String?): Boolean {
        val parts = stored?.split(':') ?: return false
        if (parts.size != 3 || parts[0] != "v1" || pin.isEmpty()) return false
        val salt = runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull() ?: return false
        val want = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(pin, salt), want)
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, ROUNDS, 256)).encoded

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}
