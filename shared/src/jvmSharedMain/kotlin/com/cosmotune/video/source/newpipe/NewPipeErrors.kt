package com.cosmotune.video.source.newpipe

import com.cosmotune.video.source.VideoSourceError
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.FoundAdException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.exceptions.UnsupportedTabException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.search.SearchExtractor
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Every NewPipe exception of spec 2.1 as one of our [VideoSourceError]s (plan task Y-4, step 5), so nothing of NewPipe's
 * is thrown across the `VideoSource` boundary.
 *
 * | NewPipe | ours |
 * | :-- | :-- |
 * | `SignInConfirmNotBotException`, `ReCaptchaException` (HTTP 429) | `BotCheck` |
 * | `AgeRestrictedContentException` | `AgeRestricted` |
 * | a "for kids" message of any of them | `MadeForKids` |
 * | `PrivateContentException` | `Private` |
 * | `PaidContentException`, `YoutubeMusicPremiumContentException` | `Paid` |
 * | `GeographicRestrictionException`, `UnsupportedContentInCountryException` | `GeoBlocked` |
 * | `AccountTerminatedException`, any other `ContentNotAvailableException`, `ContentNotSupportedException`, `FoundAdException`, `NothingFoundException`, `UnsupportedTabException` | `NotAvailable` |
 * | an `IOException` or a failure caused by one | `Network` |
 * | `ParsingException`, any other `ExtractionException`, anything else that escapes the extractor | `Parsing` |
 */
internal object NewPipeErrors {

    /** The message YouTube gives for a video made for kids, which the anonymous player refuses ("made for kids", "for kids"). */
    private val MADE_FOR_KIDS = Regex("\\bfor kids\\b", RegexOption.IGNORE_CASE)

    fun map(error: Throwable): VideoSourceError {
        if (error is VideoSourceError) return error
        val message = error.message ?: error.javaClass.simpleName
        if (error is ContentNotAvailableException || error is org.schabi.newpipe.extractor.exceptions.ParsingException) {
            if (MADE_FOR_KIDS.containsMatchIn(message)) return VideoSourceError.MadeForKids(message)
        }
        return when (error) {
            is SignInConfirmNotBotException -> VideoSourceError.BotCheck(message)
            is ReCaptchaException -> VideoSourceError.BotCheck(message)
            is AgeRestrictedContentException -> VideoSourceError.AgeRestricted(message)
            is PrivateContentException -> VideoSourceError.Private(message)
            is PaidContentException, is YoutubeMusicPremiumContentException -> VideoSourceError.Paid(message)
            is GeographicRestrictionException, is UnsupportedContentInCountryException -> VideoSourceError.GeoBlocked(message)
            is AccountTerminatedException -> VideoSourceError.NotAvailable(message)
            is ContentNotAvailableException -> VideoSourceError.NotAvailable(message)
            is ContentNotSupportedException, is FoundAdException -> VideoSourceError.NotAvailable(message)
            is SearchExtractor.NothingFoundException, is UnsupportedTabException -> VideoSourceError.NotAvailable(message)
            is IOException -> VideoSourceError.Network(message, error)
            else -> {
                val io = causeChain(error).firstOrNull { it is IOException }
                if (io != null) VideoSourceError.Network(message, io) else VideoSourceError.Parsing(message, error)
            }
        }
    }

    /**
     * Runs [block]; whatever it throws leaves as a [VideoSourceError]. Cancellation passes through untouched (it is how a
     * coroutine stops, not a failure of the source). A linkage error (a method the extractor on this device lacks) is a
     * [VideoSourceError.Parsing], the same "an update may fix this" as a changed answer.
     */
    inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: VideoSourceError) {
        throw e
    } catch (e: Exception) {
        throw map(e)
    } catch (e: LinkageError) {
        throw map(e)
    }

    /** Whether [error] is the extractor's "nothing found" for a search: an empty result, not a failure. */
    fun isNothingFound(error: Throwable): Boolean = error is SearchExtractor.NothingFoundException

    /** The first of [errors] that is a real failure, that is, not a search that found nothing; null if there is none. */
    fun firstRealError(errors: List<Throwable>): Throwable? = errors.firstOrNull { !isNothingFound(it) }

    private fun causeChain(error: Throwable): Sequence<Throwable> =
        generateSequence(error.cause) { it.cause }.take(MAX_CAUSE_DEPTH)

    private const val MAX_CAUSE_DEPTH = 8
}
