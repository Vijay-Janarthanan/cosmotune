package com.cosmotune.manga.extensions

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import eu.kanade.tachiyomi.source.preferenceKey
import androidx.preference.PreferenceFragmentCompat
import com.cosmotune.manga.data.MangaStore
import com.cosmotune.manga.data.ParserContext
import com.cosmotune.manga.data.platformMangaFileProtection
import eu.kanade.tachiyomi.source.ConfigurableSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MangaExtensionSettingsActivity : AppCompatActivity() {
    internal var source: ConfigurableSource? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        // Extension preferences persist themselves; recreate their screen from the pinned source on restoration.
        super.onCreate(null)
        val container = FrameLayout(this).apply { id = android.view.View.generateViewId() }
        setContentView(container)
        lifecycleScope.launch {
            try {
                source = withContext(Dispatchers.IO) {
                    val store = MangaStore(File(filesDir, "manga"), platformMangaFileProtection())
                    val config = store.state.value.sources.first { it.id == intent.getStringExtra("sourceId") }
                    MangaExtensions(applicationContext, store, ParserContext(store.root, store.protection) { store.state.value.sources }).preferences(config)
                }
                title = requireNotNull(source).name
                supportFragmentManager.beginTransaction().replace(container.id, SourcePreferences()).commit()
            } catch (error: Exception) { container.addView(TextView(this@MangaExtensionSettingsActivity).apply { text = error.message; setPadding(24, 24, 24, 24) }) }
        }
    }
    class SourcePreferences : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val source = (requireActivity() as MangaExtensionSettingsActivity).source ?: return
            preferenceManager.sharedPreferencesName = source.preferenceKey()
            preferenceManager.preferenceDataStore = SourcePreferenceDataStore(source.getSourcePreferences())
            val screen = preferenceManager.createPreferenceScreen(requireContext())
            try { source.setupPreferenceScreen(screen) }
            catch (_: Exception) { screen.addPreference(Preference(requireContext()).apply { title = "Source settings are unavailable"; summary = "Update this extension and try again." }) }
            catch (_: LinkageError) { screen.addPreference(Preference(requireContext()).apply { title = "This extension needs an unavailable settings API"; summary = "Update or disable the extension." }) }
            preferenceScreen = screen
        }
    }
}
