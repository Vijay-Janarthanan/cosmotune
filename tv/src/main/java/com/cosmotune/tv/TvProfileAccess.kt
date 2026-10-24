package com.cosmotune.tv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A verified selection belongs to one profile, never to an entire app session. */
internal class TvProfileAccess {
    private val verified = MutableStateFlow<String?>(null)
    val verifiedProfileId = verified.asStateFlow()
    fun verifiedSelection(profileId: String) { require(profileId.isNotBlank()); verified.value = profileId }
    fun openAccountPicker() { verified.value = null }
    fun allows(profileId: String?, locked: Boolean, proof: String? = verified.value): Boolean =
        !locked || profileId != null && profileId == proof
}
