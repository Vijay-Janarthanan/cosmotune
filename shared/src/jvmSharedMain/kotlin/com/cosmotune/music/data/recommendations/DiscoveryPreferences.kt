package com.cosmotune.music.data.recommendations

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DiscoveryRegion(val country: String = Locale.getDefault().country.ifBlank { "IN" }, val musicLanguage: String = "") {
    fun validated(): DiscoveryRegion = apply {
        require(country == "ZZ" || country in Locale.getISOCountries()) { "Choose a country or Global" }
        require(musicLanguage.isEmpty() || musicLanguage.matches(Regex("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})?"))) { "Choose a music language" }
    }
    val countryLabel: String get() = if (country == "ZZ") "Global" else Locale.Builder().setRegion(country).build().getDisplayCountry(Locale.ENGLISH)
    val languageLabel: String get() = if (musicLanguage.isEmpty()) "Any language" else Locale.forLanguageTag(musicLanguage).getDisplayLanguage(Locale.ENGLISH)
}

/** Music discovery preferences are independent of interface language and source credentials. */
object DiscoveryPreferences {
    val countries = listOf("IN" to "India", "ZZ" to "Global", "US" to "United States", "GB" to "United Kingdom")
    val languages = listOf("" to "All languages", "en" to "English", "hi" to "Hindi", "ta" to "Tamil",
        "te" to "Telugu", "ml" to "Malayalam", "kn" to "Kannada", "mr" to "Marathi", "bn" to "Bengali",
        "pa" to "Punjabi", "gu" to "Gujarati", "or" to "Odia", "as" to "Assamese")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow(DiscoveryRegion())
    val state = mutable.asStateFlow()
    private var file: File? = null
    private var readable = true

    @Synchronized fun configure(path: File) {
        if (file == path) return
        file = path
        readable = true
        mutable.value = if (!path.exists()) DiscoveryRegion() else try {
            require(path.length() <= 16_384)
            json.decodeFromString<DiscoveryRegion>(path.readText()).validated()
        } catch (_: Exception) { readable = false; DiscoveryRegion() }
    }

    /** Called on IO; publish only after the preference is safely saved. */
    @Synchronized fun save(value: DiscoveryRegion) {
        value.validated()
        val destination = file
        if (destination != null) {
            check(readable) { "Existing discovery preferences could not be read" }
            destination.parentFile.mkdirs()
            val staged = File.createTempFile("discovery-", ".tmp", destination.parentFile)
            try {
                staged.writeText(json.encodeToString(value))
                Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { staged.delete() }
        }
        mutable.value = value
    }
}
