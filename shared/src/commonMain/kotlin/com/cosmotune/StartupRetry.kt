package com.cosmotune

/**
 * One automatic second try of the first Home load, for the case that failed only because a host name could
 * not be looked up. Right after a fresh install, a handover from Wi-Fi to mobile data or a wake-up from
 * sleep, the phone can have no usable network for a moment: the first request then fails with
 * `Unable to resolve host "...": No address associated with hostname`, and the feed used to stay on its
 * "No internet connection" error until the person pressed Retry. A few seconds later the same request works.
 *
 * Only the decision lives here (so it can be tested without a phone); the waiting and the second load are
 * in `MainViewModel`. Any other failure (a rejected request, a timeout, a server error) is not retried: the
 * error is shown at once, as before.
 */
object StartupRetry {

    /** How long the feed waits, still showing its loading placeholders, before the second try. */
    const val DELAY_MS: Long = 3000L

    /**
     * The wordings of a failed host-name lookup, compared without regard to capital letters. The first two are
     * the phrases `MainViewModel.friendly()` already turns into "No internet connection" (Android's
     * `Unable to resolve host "...": ...` is the usual one); the other two are what the system adds after the
     * colon (`No address associated with hostname`) or says on its own elsewhere
     * (`Temporary failure in name resolution`).
     */
    private val phrases = listOf(
        "resolve host",
        "Unable to resolve",
        "name resolution",
        "No address associated with hostname",
    )

    /** True when [message], the text of the exception a request failed with, says that a host name could not be looked up. */
    fun isNameResolutionFailure(message: String?): Boolean =
        message != null && phrases.any { message.contains(it, ignoreCase = true) }

    /**
     * Whether the failed first load gets its second try: only for a name-resolution failure, and only while
     * [alreadyRetried] is false (the one try is used up once it has been scheduled).
     */
    fun shouldRetry(message: String?, alreadyRetried: Boolean): Boolean =
        !alreadyRetried && isNameResolutionFailure(message)
}
