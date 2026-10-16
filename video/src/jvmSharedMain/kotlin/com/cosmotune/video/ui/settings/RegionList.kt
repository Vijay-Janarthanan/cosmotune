package com.cosmotune.video.ui.settings

import androidx.compose.runtime.Immutable
import java.text.Collator
import java.util.IllformedLocaleException
import java.util.Locale

/*
 * The lists behind "Region for Home", "Content language" and the captions' default language (spec 5.11), from the JDK's own ISO lists
 * so that no table of countries is kept here. The names are the device's language's, sorted the way that language sorts. Pure.
 */

/**
 * One entry of a place or language list. [code] is what is stored in the settings (an ISO 3166 country such as "IN", or an ISO 639
 * language such as "en"); null is the first entry of a list ("Device", or "Off" for the captions): the setting is unset.
 */
@Immutable
internal data class PlaceChoice(val code: String?, val name: String)

/** "Device" (or [first]) and then every country the JDK names in [inLocale]'s language, sorted by name. */
internal fun regionChoices(inLocale: Locale, first: String): List<PlaceChoice> =
    listOf(PlaceChoice(null, first)) + sortedByName(
        Locale.getISOCountries().mapNotNull { code -> named(code, regionName(code, inLocale)) },
        inLocale,
    )

/** [first] ("Device" or "Off") and then every language the JDK names in [inLocale]'s language, sorted by name, one entry for each name. */
internal fun languageChoices(inLocale: Locale, first: String): List<PlaceChoice> =
    listOf(PlaceChoice(null, first)) + sortedByName(
        Locale.getISOLanguages().mapNotNull { code -> named(code, languageName(code, inLocale)) }.distinctBy { it.name },
        inLocale,
    )

/** The name of the country [code] in [inLocale]'s language, or the code itself when the JDK has no name for it. */
internal fun regionName(code: String, inLocale: Locale): String {
    // A stored value is whatever the file holds: one that is no region at all is shown as it is.
    val region = try {
        Locale.Builder().setRegion(code.uppercase()).build()
    } catch (e: IllformedLocaleException) {
        return code
    }
    return region.getDisplayCountry(inLocale).ifBlank { code }
}

/**
 * The name of the language [tag] ("en", or "en-GB" with its region, which the name then says) in [inLocale]'s language, or the tag
 * itself when the JDK has no name for it.
 */
internal fun languageName(tag: String, inLocale: Locale): String {
    val locale = Locale.forLanguageTag(tag.replace('_', '-'))
    return locale.getDisplayName(inLocale).ifBlank { tag }
}

/** An entry for [code], or null when [name] is only the code again (a code the JDK knows no name for is no use to a person). */
private fun named(code: String, name: String): PlaceChoice? = if (name.isBlank() || name.equals(code, ignoreCase = true)) null else PlaceChoice(code, name)

/** [entries] in the order [inLocale] sorts its words (accents and case do not decide). */
private fun sortedByName(entries: List<PlaceChoice>, inLocale: Locale): List<PlaceChoice> {
    val collator = Collator.getInstance(inLocale).also { it.strength = Collator.PRIMARY }
    return entries.sortedWith { a, b -> collator.compare(a.name, b.name) }
}
