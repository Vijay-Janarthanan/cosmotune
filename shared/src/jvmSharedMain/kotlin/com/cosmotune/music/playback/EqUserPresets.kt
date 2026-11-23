package com.cosmotune.music.playback

/** An equaliser curve the listener saved under their own name (the equaliser's Your presets). */
data class EqUserPreset(val name: String, val bands: List<Float>)

/** Your presets, kept as one line each ("name<TAB>gain,gain,…") in the settings store. */
object EqUserPresets {
    const val MAX = 24
    const val MAX_NAME = 40

    fun encode(presets: List<EqUserPreset>): String =
        presets.joinToString("\n") { preset -> clean(preset.name) + "\t" + preset.bands.joinToString(",") { "%.2f".format(java.util.Locale.ROOT, it) } }

    fun decode(text: String): List<EqUserPreset> = text.lineSequence().mapNotNull { line ->
        val name = line.substringBefore('\t', "").let(::clean).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val bands = line.substringAfter('\t', "").split(',').map { it.trim().toFloatOrNull() ?: return@mapNotNull null }
        EqUserPreset(name, bands.map { it.coerceIn(-12f, 12f) }).takeIf { bands.isNotEmpty() }
    }.distinctBy { it.name.lowercase() }.take(MAX).toList()

    /** [presets] with [bands] saved as [name]: a preset of the same name (any case) is replaced in place, else added first. */
    fun save(presets: List<EqUserPreset>, name: String, bands: List<Float>): List<EqUserPreset> {
        val tidy = clean(name).ifEmpty { return presets }
        val saved = EqUserPreset(tidy, bands)
        val at = presets.indexOfFirst { it.name.equals(tidy, ignoreCase = true) }
        return (if (at >= 0) presets.toMutableList().also { it[at] = saved } else listOf(saved) + presets).take(MAX)
    }

    fun remove(presets: List<EqUserPreset>, name: String): List<EqUserPreset> = presets.filterNot { it.name == name }

    /** The saved preset these bands are, if any, so its chip can show as chosen. */
    fun matching(presets: List<EqUserPreset>, bands: List<Float>): EqUserPreset? = presets.firstOrNull { preset ->
        preset.bands.size == bands.size && preset.bands.indices.all { kotlin.math.abs(preset.bands[it] - bands[it]) < 0.05f }
    }

    private fun clean(name: String) = name.replace(Regex("[\\t\\r\\n]+"), " ").trim().take(MAX_NAME)
}
