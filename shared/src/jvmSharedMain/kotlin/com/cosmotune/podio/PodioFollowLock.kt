package com.cosmotune.podio

import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.Serializable

/** A PIN that following new shows asks for (AntennaPod's parental control). Only its salted hash is kept. */
@Serializable data class PodioFollowLockPin(val salt: String, val hash: String)

object PodioFollowLock {
    /** Four to eight digits. */
    fun valid(pin: String): Boolean = pin.length in 4..8 && pin.all(Char::isDigit)

    fun lock(pin: String, random: SecureRandom = SecureRandom()): PodioFollowLockPin {
        require(valid(pin)) { "Use 4 to 8 digits." }
        val salt = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        return PodioFollowLockPin(salt, hash(salt, pin))
    }

    fun matches(lock: PodioFollowLockPin?, pin: String): Boolean =
        lock == null || MessageDigest.isEqual(lock.hash.toByteArray(), hash(lock.salt, pin.trim()).toByteArray())

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
}
