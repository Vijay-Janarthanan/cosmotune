package com.cosmotune.reading.data

import kotlinx.serialization.Serializable

/** Catalogue metadata is independent of Manga image chapters and of the platform document reader. */
@Serializable data class ReadingPublication(
    val id: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val description: String = "",
    val identifiers: List<String> = emptyList(),
    val links: List<ReadingLink> = emptyList(),
    val coverUrl: String? = null,
    /** Raw provider evidence, not a claim that a commercial book is free or downloadable. */
    val accessEvidence: String = "metadata-only",
    val sourceId: String = "",
    /** Physical edition labels reported by the catalogue; these are not digital file formats. */
    val editionFormats: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    val publishedDate: String? = null,
)

@Serializable enum class ReadingLinkKind { BROWSE, SEARCH, ACQUIRE, SAMPLE, BORROW, BUY, DETAILS, OTHER }

@Serializable data class ReadingLink(val title: String, val url: String, val mediaType: String = "", val relation: String = "",
    val kind: ReadingLinkKind = ReadingLinkKind.OTHER) {
    private val mime get() = mediaType.substringBefore(';').trim().lowercase()

    val downloadFormat: ReadingFormat? get() = when {
        kind != ReadingLinkKind.ACQUIRE -> null
        mime == "application/epub+zip" -> ReadingFormat.EPUB
        mime == "application/pdf" -> ReadingFormat.PDF
        else -> null
    }

    val requiresExternalReader: Boolean get() = when (mime) {
        "application/vnd.readium.lcp.license.v1.0+json", "application/pdf+lcp", "application/vnd.adobe.adept+xml" -> true
        else -> false
    }
}

data class ReadingCataloguePage(val sourceId: String, val title: String, val publications: List<ReadingPublication>,
    val navigation: List<ReadingLink> = emptyList(), val next: ReadingLink? = null,
    val search: List<ReadingLink> = emptyList())

@Serializable data class ReadingSearchPage(val sourceId: String, val publications: List<ReadingPublication>, val total: Long, val nextOffset: Int?)
