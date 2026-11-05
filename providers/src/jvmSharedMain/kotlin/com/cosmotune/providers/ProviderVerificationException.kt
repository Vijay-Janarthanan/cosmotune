package com.cosmotune.providers

/** Only controlled protocol metadata; never includes an operator response body, URL or grant. */
class ProviderVerificationException(val httpStatus: Int, val operatorCode: String?) :
    IllegalArgumentException("Provider verification request failed (HTTP $httpStatus${operatorCode?.let { ": $it" }.orEmpty()})")

/** Access state is actionable even when the operator could not mint a challenge URL. */
class ProviderAccessRequiredException : IllegalStateException("Provider sign-in or verification required")

/** The provider's bounded error category and HTTP status, without its message or signed address. */
class ProviderResolutionException(val errorType: String?, val httpStatus: Int?, val hostApi: String? = null, val hostFailureKind: String? = null) :
    IllegalArgumentException("Provider could not resolve this recording${errorType?.let { " ($it)" }.orEmpty()}${httpStatus?.let { " · HTTP $it" }.orEmpty()}")

/** The package has no track search method (`searchTracks`, `customSearch` or `search`). */
class ProviderSearchUnsupportedException : IllegalStateException("This provider does not offer search")

/** Whether a failed provider request needs the listener's sign-in or human verification before it can succeed. */
internal fun Throwable.needsProviderAccess(): Boolean =
    this is ProviderAccessRequiredException || this is ProviderVerificationException && httpStatus in setOf(401, 403, 428)
