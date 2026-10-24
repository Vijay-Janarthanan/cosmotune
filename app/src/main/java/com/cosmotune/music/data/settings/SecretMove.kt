package com.cosmotune.music.data.settings

/**
 * Moving one credential out of the plain settings (which Android backs up) into
 * the encrypted store (which it does not). The encrypted copy wins when both
 * exist; a plain copy is always removed once read, so it does not linger in
 * the next backup.
 */
internal object SecretMove {
    data class Resolved(
        /** What the setting holds now. */
        val value: String,
        /** What to write to the encrypted store, or null to leave it as it is. */
        val writeEncrypted: String?,
        /** Whether the plain copy has to be deleted. */
        val removePlain: Boolean,
    )

    fun resolve(plain: String?, encrypted: String?): Resolved {
        val kept = encrypted?.takeIf { it.isNotBlank() }
        val legacy = plain?.takeIf { it.isNotBlank() }
        return Resolved(
            value = kept ?: legacy.orEmpty(),
            writeEncrypted = if (kept == null) legacy else null,
            removePlain = plain != null,
        )
    }
}
