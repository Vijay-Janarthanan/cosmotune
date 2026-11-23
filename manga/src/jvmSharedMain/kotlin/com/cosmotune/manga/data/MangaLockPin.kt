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

/** How "Forgot PIN?" lets the listener back into Manga without letting a passer-by in. */
sealed interface MangaPinReset {
    /** The computer's own sign-in ([prompt], such as "your Windows password") vouches for the listener; nothing is cleared. */
    data class SignIn(val prompt: String) : MangaPinReset
    /** Nothing on the computer can vouch for the listener: the lock goes off, and what it guarded is cleared. */
    data object ClearAndUnlock : MangaPinReset
}

/** The steps of the locked page: the PIN, the reset explained, the computer's sign-in running, a new PIN, or reset done. */
enum class MangaPinStep { ENTER, EXPLAIN, CHECKING, NEW_PIN, CLEARED }

/** Where "Forgot PIN?" stands. Only a sign-in that was started and passed reaches [MangaPinStep.NEW_PIN]. */
data class MangaPinRecoveryFlow(val step: MangaPinStep = MangaPinStep.ENTER, val message: String? = null) {
    fun forgot() = if (step == MangaPinStep.ENTER) MangaPinRecoveryFlow(MangaPinStep.EXPLAIN) else this
    /** The listener read the explanation and goes on with [plan]. */
    fun confirm(plan: MangaPinReset) = if (step != MangaPinStep.EXPLAIN) this else when (plan) {
        is MangaPinReset.SignIn -> MangaPinRecoveryFlow(MangaPinStep.CHECKING)
        MangaPinReset.ClearAndUnlock -> MangaPinRecoveryFlow(MangaPinStep.CLEARED)
    }
    /** The computer's sign-in answered: [passed] or not (cancelled, wrong, or unavailable). */
    fun checked(passed: Boolean) = if (step != MangaPinStep.CHECKING) this
        else if (passed) MangaPinRecoveryFlow(MangaPinStep.NEW_PIN) else MangaPinRecoveryFlow(MangaPinStep.EXPLAIN, "The sign-in did not go through. Manga stays locked.")
    fun cancel() = MangaPinRecoveryFlow()
}

object MangaPinRecovery {
    fun plan(prompt: String?): MangaPinReset = prompt?.trim()?.takeIf { it.isNotEmpty() }?.let { MangaPinReset.SignIn(it) } ?: MangaPinReset.ClearAndUnlock

    /** What the reset does, in the listener's words, shown before it happens. */
    fun explanation(plan: MangaPinReset): String = when (plan) {
        is MangaPinReset.SignIn -> "Sign in with ${plan.prompt} to choose a new Manga PIN. Nothing is cleared."
        MangaPinReset.ClearAndUnlock -> "This computer has no sign-in Manga can check. Resetting turns the lock off and clears your Manga history " +
            "(where you stopped in each series, and when you read) and your hidden images. Your library, downloads and read chapters stay."
    }

    /** Signed in: the old PIN goes and [newPinHash] takes its place; none turns the lock off. Nothing else changes. */
    fun afterSignIn(state: MangaLibraryState, newPinHash: String?): MangaLibraryState = state.copy(lockPin = newPinHash, lockManga = newPinHash != null)

    /** No sign-in to vouch: the lock goes off, and the history and hidden images it guarded are cleared. */
    fun afterClear(state: MangaLibraryState): MangaLibraryState = state.copy(lockManga = false, lockPin = null, progress = emptyMap(),
        readLog = emptyMap(), readingTime = emptyMap(), hiddenImages = emptyMap(), activeBookId = null, activeChapterId = null)
}
