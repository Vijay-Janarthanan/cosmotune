package com.cosmotune.video.source.newpipe

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

/**
 * The one NewPipe of the process, shared by Music and Video (plan decision W1, task Y-4).
 *
 * `NewPipe.init` is process-wide and Music's resolver already calls it with a downloader that answers the
 * `/youtubei/v1/next` endpoint with an empty envelope (Music never reads the answer, and that request used to hang).
 * Video needs the real answer: related videos, likes, chapters and the comment continuation come from it. So nothing but
 * this object calls `NewPipe.init`: it installs a router, and the router sends a request
 *
 *  - to Video's downloader ([VideoDownloader]: its own HTTP client, no cookies) when the calling thread is inside
 *    [video], and
 *  - to Music's downloader (the one given to [install]) everywhere else, or to Video's while Music has not arrived.
 *
 * The extractor's calls are blocking and run on the thread that makes them (an `Extractor` holds the router and asks it
 * at the moment of each fetch), which is what makes a thread-local reliable here; `NewPipeRoutingTest` pins it. Nothing
 * inside a `video { }` block may hand NewPipe work to another thread.
 */
object NewPipeHost {
    private const val DEFAULT_LANGUAGE = "en"
    private const val DEFAULT_COUNTRY = "GB"

    private val lock = Any()
    private val router = Router()

    /** True on the threads that are inside [video]; null and false both mean "Music's". */
    private val scope = ThreadLocal<Boolean?>()

    @Volatile
    private var music: Downloader? = null

    /** Video's own downloader, built the first time Video asks (Music alone never builds an HTTP client for it). */
    private val defaultVideoDownloader: Downloader by lazy { VideoDownloader() }

    @Volatile
    private var replacedVideoDownloader: Downloader? = null

    /** Video's downloader; tests replace it (with a replay or a recorder) through the internal setter. */
    internal var videoDownloader: Downloader
        get() = replacedVideoDownloader ?: defaultVideoDownloader
        set(value) {
            replacedVideoDownloader = value
        }

    private var language = DEFAULT_LANGUAGE
    private var country = DEFAULT_COUNTRY

    /**
     * Remembers Music's downloader and makes sure the router is NewPipe's. Music's resolver calls this once, lazily,
     * in the place where it used to call `NewPipe.init`; calling it again keeps the one router and replaces only the
     * downloader Music's requests go to.
     */
    fun install(music: Downloader) {
        this.music = music
        ensure()
    }

    /**
     * Puts the router in NewPipe if it is not there: once per process, with the defaults `NewPipe.init(downloader)` itself
     * uses (English, Great Britain; read with `javap -c` at v0.26.3), so Music sees no change. If another piece of code
     * initialised NewPipe since, the router is put back with the region Video last asked for. Before Music has installed
     * its downloader every request goes to Video's.
     */
    fun ensure() {
        if (NewPipe.getDownloader() === router) return
        synchronized(lock) {
            if (NewPipe.getDownloader() !== router) {
                NewPipe.init(router, Localization(language, country), ContentCountry(country))
            }
        }
    }

    /**
     * Runs [block] with this thread marked as Video's: every request NewPipe makes on this thread while [block] runs goes
     * to Video's downloader. The previous mark is restored when [block] ends, however it ends, so scopes nest.
     */
    fun <T> video(block: () -> T): T {
        val previous = scope.get()
        scope.set(true)
        try {
            return block()
        } finally {
            if (previous == null) scope.remove() else scope.set(previous)
        }
    }

    /**
     * The region and the language of what NewPipe fetches (`NewPipe.setupLocalization`). It changes the text of the
     * answers and the country of the charts; Music's fallback only reads streams, which it does not change.
     * Blank values mean the defaults; the country is stored in upper case and the language in lower case.
     */
    fun setLocalization(country: String, language: String) {
        val newCountry = country.trim().uppercase().ifEmpty { DEFAULT_COUNTRY }
        val newLanguage = language.trim().lowercase().ifEmpty { DEFAULT_LANGUAGE }
        synchronized(lock) {
            this.country = newCountry
            this.language = newLanguage
            NewPipe.setupLocalization(Localization(newLanguage, newCountry), ContentCountry(newCountry))
        }
    }

    /** The localization NewPipe currently uses (what the stream addresses' User-Agents are built with). */
    internal fun localization(): Localization = synchronized(lock) { Localization(language, country) }

    /** Whether the calling thread is inside [video]. */
    internal fun isInVideoScope(): Boolean = scope.get() == true

    /**
     * Back to a process in which nothing has initialised NewPipe yet: no Music downloader, the default region, the real Video
     * downloader, no scope on this thread, and NewPipe holding a downloader that refuses every request (so the next [ensure]
     * has the router to put in).
     */
    internal fun resetForTests() {
        synchronized(lock) {
            music = null
            replacedVideoDownloader = null
            language = DEFAULT_LANGUAGE
            country = DEFAULT_COUNTRY
            scope.remove()
            NewPipe.init(Unconfigured, Localization(DEFAULT_LANGUAGE, DEFAULT_COUNTRY), ContentCountry(DEFAULT_COUNTRY))
        }
    }

    /** What NewPipe holds in a test before [ensure] has run. */
    private object Unconfigured : Downloader() {
        override fun execute(request: Request): Response = error("NewPipe has not been given the router: call NewPipeHost.ensure() first")
    }

    /** The downloader NewPipe sees: Video's inside [video], Music's (or Video's, before Music installs) elsewhere. */
    private class Router : Downloader() {
        override fun execute(request: Request): Response {
            val target = if (scope.get() == true) videoDownloader else (music ?: videoDownloader)
            return target.execute(request)
        }
    }
}
