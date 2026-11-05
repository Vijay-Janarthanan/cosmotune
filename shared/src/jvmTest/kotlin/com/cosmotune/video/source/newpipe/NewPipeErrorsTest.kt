package com.cosmotune.video.source.newpipe

import com.cosmotune.video.source.VideoSourceError
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.FoundAdException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.exceptions.UnsupportedTabException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.search.SearchExtractor
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Every NewPipe exception of spec 2.1 as one of our `VideoSourceError`s (plan task Y-4, step 5). */
class NewPipeErrorsTest {

    @Test
    fun theSignInToConfirmNotABotErrorIsABotCheck() {
        val mapped = NewPipeErrors.map(SignInConfirmNotBotException("probably temporarily blocked anonymous watch access with this IP"))
        assertIs<VideoSourceError.BotCheck>(mapped)
        assertEquals("probably temporarily blocked anonymous watch access with this IP", mapped.message)
    }

    @Test
    fun aRateLimitedAnswerIsABotCheck() {
        assertIs<VideoSourceError.BotCheck>(NewPipeErrors.map(ReCaptchaException("reCaptcha Challenge requested", "https://www.youtube.com/watch?v=x")))
    }

    @Test
    fun anAgeRestrictedVideoIsAgeRestricted() {
        assertIs<VideoSourceError.AgeRestricted>(NewPipeErrors.map(AgeRestrictedContentException("This age-restricted video cannot be watched anonymously")))
    }

    @Test
    fun theMadeForKidsMessageIsMadeForKids() {
        assertIs<VideoSourceError.MadeForKids>(NewPipeErrors.map(ContentNotAvailableException("Got error: \"This video is made for kids and cannot be played here\"")))
        assertIs<VideoSourceError.MadeForKids>(NewPipeErrors.map(ContentNotAvailableException("Made For Kids")))
        assertIs<VideoSourceError.MadeForKids>(NewPipeErrors.map(ParsingException("The content is for kids: sign in")))
    }

    @Test
    fun aMessageWithoutKidsIsNotMadeForKids() {
        assertFalse(NewPipeErrors.map(ContentNotAvailableException("This video is unavailable")) is VideoSourceError.MadeForKids)
        assertFalse(NewPipeErrors.map(ContentNotAvailableException("Skidsby is not a word we match")) is VideoSourceError.MadeForKids)
    }

    @Test
    fun privatePaidAndMembersOnlyContent() {
        assertIs<VideoSourceError.Private>(NewPipeErrors.map(PrivateContentException("This video is private")))
        assertIs<VideoSourceError.Paid>(NewPipeErrors.map(PaidContentException("This video is a paid video")))
        assertIs<VideoSourceError.Paid>(NewPipeErrors.map(PaidContentException("only available for members of the channel")))
        assertIs<VideoSourceError.Paid>(NewPipeErrors.map(YoutubeMusicPremiumContentException()))
    }

    @Test
    fun countryRestrictionsAreGeoBlocked() {
        assertIs<VideoSourceError.GeoBlocked>(NewPipeErrors.map(GeographicRestrictionException("This video is not available in client's country.")))
        assertIs<VideoSourceError.GeoBlocked>(NewPipeErrors.map(UnsupportedContentInCountryException("YouTube Charts doesn't support this country")))
    }

    @Test
    fun removedVideosAndTerminatedAccountsAreNotAvailable() {
        assertIs<VideoSourceError.NotAvailable>(NewPipeErrors.map(AccountTerminatedException("This account has been terminated")))
        assertIs<VideoSourceError.NotAvailable>(NewPipeErrors.map(ContentNotAvailableException("Video unavailable")))
        assertIs<VideoSourceError.NotAvailable>(NewPipeErrors.map(ContentNotSupportedException("Not supported")))
        assertIs<VideoSourceError.NotAvailable>(NewPipeErrors.map(FoundAdException("an ad")))
        assertIs<VideoSourceError.NotAvailable>(NewPipeErrors.map(SearchExtractor.NothingFoundException("nothing found")))
        assertIs<VideoSourceError.NotAvailable>(NewPipeErrors.map(UnsupportedTabException("tab")))
    }

    @Test
    fun theMessageSurvives() {
        assertEquals("This video is private", NewPipeErrors.map(PrivateContentException("This video is private")).message)
        assertEquals("Video unavailable", NewPipeErrors.map(ContentNotAvailableException("Video unavailable")).message)
    }

    @Test
    fun inputOutputFailuresAreNetworkErrorsThatKeepTheirCause() {
        val io = IOException("connection reset")
        val mapped = NewPipeErrors.map(io)
        assertIs<VideoSourceError.Network>(mapped)
        assertSame(io, mapped.cause)

        assertIs<VideoSourceError.Network>(NewPipeErrors.map(SocketTimeoutException("timeout")))
        assertIs<VideoSourceError.Network>(NewPipeErrors.map(UnknownHostException("www.youtube.com")))
        assertIs<VideoSourceError.Network>(NewPipeErrors.map(InterruptedIOException("interrupted")))
    }

    @Test
    fun aParsingErrorCausedByTheNetworkIsANetworkError() {
        val wrapped = ParsingException("Could not get the page", UnknownHostException("www.youtube.com"))
        assertIs<VideoSourceError.Network>(NewPipeErrors.map(wrapped))
        val deeper = ExtractionException("x", ParsingException("y", SocketTimeoutException("z")))
        assertIs<VideoSourceError.Network>(NewPipeErrors.map(deeper))
    }

    @Test
    fun parsingErrorsMeanTheSourceChanged() {
        val parsing = ParsingException("Could not parse JSON")
        val mapped = NewPipeErrors.map(parsing)
        assertIs<VideoSourceError.Parsing>(mapped)
        assertSame(parsing, mapped.cause)
        assertIs<VideoSourceError.Parsing>(NewPipeErrors.map(ExtractionException("Could not get any stream")))
    }

    @Test
    fun anythingElseThatEscapesTheExtractorIsAParsingError() {
        val npe = NullPointerException("x")
        val mapped = NewPipeErrors.map(npe)
        assertIs<VideoSourceError.Parsing>(mapped)
        assertSame(npe, mapped.cause)
        assertIs<VideoSourceError.Parsing>(NewPipeErrors.map(IllegalStateException("bad")))
        assertIs<VideoSourceError.Parsing>(NewPipeErrors.map(NoSuchMethodError("org.schabi.newpipe.Foo.bar")))
    }

    @Test
    fun anErrorOfOurOwnPassesThroughUnchanged() {
        val own = VideoSourceError.Private("mine")
        assertSame(own, NewPipeErrors.map(own))
    }

    @Test
    fun guardMapsWhatItsBlockThrowsAndLeavesCancellationAlone() {
        assertEquals(7, NewPipeErrors.guard { 7 })
        assertFailsWith<VideoSourceError.Private> { NewPipeErrors.guard { throw PrivateContentException("p") } }
        assertFailsWith<VideoSourceError.Network> { NewPipeErrors.guard { throw IOException("io") } }
        val cancelled = CancellationException("cancelled")
        val thrown = assertFailsWith<CancellationException> { NewPipeErrors.guard { throw cancelled } }
        assertSame(cancelled, thrown)
        val own = VideoSourceError.BotCheck("b")
        assertSame(own, assertFailsWith<VideoSourceError.BotCheck> { NewPipeErrors.guard { throw own } })
    }

    @Test
    fun nothingFoundIsRecognisedForSearch() {
        assertTrue(NewPipeErrors.isNothingFound(SearchExtractor.NothingFoundException("x")))
        assertFalse(NewPipeErrors.isNothingFound(ParsingException("x")))
        assertFalse(NewPipeErrors.isNothingFound(IOException("x")))
    }

    @Test
    fun theFirstRealErrorOfAListIgnoresNothingFound() {
        val errors = listOf<Throwable>(SearchExtractor.NothingFoundException("none"), ParsingException("real"))
        assertIs<VideoSourceError.Parsing>(NewPipeErrors.firstRealError(errors)?.let { NewPipeErrors.map(it) })
        assertEquals(null, NewPipeErrors.firstRealError(listOf(SearchExtractor.NothingFoundException("none"))))
        assertEquals(null, NewPipeErrors.firstRealError(emptyList()))
    }
}
