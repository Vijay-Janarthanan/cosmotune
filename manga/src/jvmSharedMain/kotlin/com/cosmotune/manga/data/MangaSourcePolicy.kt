package com.cosmotune.manga.data

import java.text.Normalizer
import java.util.Locale

/** The requested source-name exclusion applies to every catalogue and installation path. */
object MangaSourcePolicy {
    fun blocked(vararg identities: String): Boolean = identities.any { value ->
        Normalizer.normalize(value, Normalizer.Form.NFKD).lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit).contains("hent")
    }

    fun allowed(source: MangaSourceConfig): Boolean = !blocked(source.id, source.name, source.settings["package"].orEmpty())

    fun requireAllowed(vararg identities: String) {
        require(!blocked(*identities)) { "This manga source is excluded by the source-name filter." }
    }

    /** Remove subscriptions and active navigation only; saved books, progress and offline pages remain. */
    fun clean(state: MangaLibraryState): MangaLibraryState {
        val removed = state.sources.filterNot(::allowed).map { it.id }.toSet()
        val active = state.books.find { it.id == state.activeBookId }
        val clearActive = active?.sourceId?.let { it in removed || blocked(it) } == true
        return state.copy(sources = state.sources.filter(::allowed),
            sourceSetupConsidered = state.sourceSetupConsidered || state.sources.isNotEmpty(),
            activeBookId = state.activeBookId.takeUnless { clearActive },
            activeChapterId = state.activeChapterId.takeUnless { clearActive })
    }
}
