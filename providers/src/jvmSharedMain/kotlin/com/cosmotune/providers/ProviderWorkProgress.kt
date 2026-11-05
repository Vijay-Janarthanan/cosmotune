package com.cosmotune.providers

/** Ephemeral operation state; host-generated labels contain only phases and counts. */
data class ProviderWorkProgress(val status: String, val bytesProcessed: Long? = null, val bytesTotal: Long? = null,
    val percent: Float? = null, val segmentsCompleted: Int? = null, val segmentsTotal: Int? = null) {
    val fraction: Float? get() = percent?.takeIf { it.isFinite() }?.div(100f)?.coerceIn(0f, 1f)
        ?: bytesTotal?.takeIf { it > 0 }?.let { ((bytesProcessed ?: 0).toDouble() / it).toFloat().coerceIn(0f, 1f) }
        ?: segmentsTotal?.takeIf { it > 0 }?.let { ((segmentsCompleted ?: 0).toFloat() / it).coerceIn(0f, 1f) }
}
