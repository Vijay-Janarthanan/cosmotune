package com.cosmotune.providers

/** Only controlled protocol metadata; never includes an operator response body, URL or grant. */
class ProviderVerificationException(val httpStatus: Int, val operatorCode: String?) :
    IllegalArgumentException("Provider verification request failed (HTTP $httpStatus${operatorCode?.let { ": $it" }.orEmpty()})")

/** Access state is actionable even when the operator could not mint a challenge URL. */
class ProviderAccessRequiredException : IllegalStateException("Provider sign-in or verification required")

/** The provider's bounded error category and HTTP status, without its message or signed address. */
class ProviderResolutionException(val errorType: String?, val httpStatus: Int?, val hostApi: String? = null, val hostFailureKind: String? = null) :
    IllegalArgumentException("Provider could not resolve this recording${errorType?.let { " ($it)" }.orEmpty()}${httpStatus?.let { " · HTTP $it" }.orEmpty()}")
