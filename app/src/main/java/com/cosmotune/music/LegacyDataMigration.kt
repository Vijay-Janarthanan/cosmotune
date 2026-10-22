package com.cosmotune.music

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.cosmotune.music.auth.EncryptedPrefs
import java.io.File

/**
 * Copies what the app stored under the names it had before the rename (FORK.md, the rename row) to its own names,
 * once.
 *
 * Until the rename the code still carried the upstream project's name, and so did the files it kept its state in:
 * the settings, the Listen Together party, local playlists, the restart queue, the widget's last picture and the two
 * encrypted stores (the signed-in sessions and the music sources). Every one of them now opens a file with the new
 * name, so without this copy an update would look like a fresh install.
 *
 * The rules:
 * - It runs first in [CosmotuneApplication.onCreate], before anything opens a store.
 * - It copies and never deletes: the old files stay on the device for a later cleanup (and stay excluded from
 *   backups, see `backup_rules.xml`).
 * - A value already present under the new name wins: on a retry after an interrupted copy, the newer value is the
 *   one the app has written since.
 * - When everything is copied it sets [FLAG_KEY] in the new store [FLAG_STORE], and later starts do nothing but read
 *   that flag. A store whose new file cannot be written leaves the flag unset, so the next start tries again; an old
 *   store that cannot be read at all (an encrypted file restored without its Keystore key) is skipped for good, since
 *   no later start can read it either.
 * - Nothing in here can stop the app from starting: every failure is caught, logged and passed over.
 *
 * What it cannot move: notification channel ids (the old Music playback channel is deleted, so the system list
 * shows one "Playback" entry; its sound and importance start from the defaults again) and home-screen widgets,
 * which the system files under the receiver's class name and drops when that class is gone; they have to be added
 * again. The public `Music/Cosmotune` download folder never carried the old name, so nothing there moves.
 *
 * This file names the old names on purpose and is on the allowlist of `OldNameScanTest`.
 */
internal object LegacyDataMigration {

    private const val TAG = "Cosmotune"

    /** The store that holds the done flag, under the new names. */
    const val FLAG_STORE = "cosmotune_migration"
    const val FLAG_KEY = "legacy_names_copied_v1"

    /** Plain SharedPreferences files: the name before the rename to the name now. */
    val PLAIN_STORES: List<Pair<String, String>> = listOf(
        "bitchord_settings" to "cosmotune_settings",
        "bitchord_listen_together" to "cosmotune_listen_together",
        "bitchord_local_playlists" to "cosmotune_local_playlists",
        "bitchord_last_played" to "cosmotune_last_played",
        "bitchord_original_versions" to "cosmotune_original_versions",
        "bitchord_party_queue_stash" to "cosmotune_party_queue_stash",
        "bitchord_widget" to "cosmotune_widget",
    )

    /** An [EncryptedPrefs] store: its encrypted file and the plain fallback beside it, before and after. */
    class EncryptedStore(val old: String, val oldPlain: String, val new: String, val newPlain: String)

    val ENCRYPTED_STORES: List<EncryptedStore> = listOf(
        EncryptedStore("bitchord_auth", "bitchord_auth_plain", "cosmotune_auth", "cosmotune_auth_plain"),
        EncryptedStore("bitchord_sources", "bitchord_sources_plain", "cosmotune_sources", "cosmotune_sources_plain"),
    )

    /** Music's playback notification channel before the rename (now `cosmotune_playback`). */
    const val LEGACY_PLAYBACK_CHANNEL = "bitchord_playback"

    /** The `app` field of a listening-history backup written before the rename; such a file still imports. */
    const val LEGACY_BACKUP_TAG = "bitchord"

    // ---- The copy itself, free of Android so that a plain unit test can drive it ----------------------------

    /** A key-value store as SharedPreferences sees one. */
    interface Store {
        fun read(): Map<String, Any?>

        /** Writes every entry and reports whether it reached the store. */
        fun write(values: Map<String, Any?>): Boolean
    }

    /** The done flag. */
    interface Flag {
        fun isSet(): Boolean
        fun set()
    }

    /**
     * One store to copy. [source] gives null when nothing was ever stored under the old name; it may throw when the
     * old store exists but cannot be opened. [target] opens the store of the new name.
     */
    class Job(val label: String, val source: () -> Store?, val target: () -> Store)

    enum class Outcome { ALREADY_DONE, COPIED, RETRY_LATER }

    fun copy(flag: Flag, jobs: List<Job>, log: (String, Throwable?) -> Unit): Outcome {
        val done = try {
            flag.isSet()
        } catch (t: Throwable) {
            log("could not read the migration flag; trying again on the next start", t)
            return Outcome.RETRY_LATER
        }
        if (done) return Outcome.ALREADY_DONE
        var complete = true
        for (job in jobs) {
            val old = try {
                job.source()?.read().orEmpty()
            } catch (t: Throwable) {
                // Unreadable now means unreadable on every later start too: skipped, never retried.
                log("${job.label}: the old store cannot be read; skipped", t)
                continue
            }
            val values = old.filterValues(::isStorable)
            if (values.isEmpty()) continue
            try {
                val target = job.target()
                val existing = target.read()
                val missing = values.filterKeys { it !in existing }
                if (missing.isEmpty()) continue
                if (target.write(missing)) {
                    log("${job.label}: copied ${missing.size} of ${values.size} entries", null)
                } else {
                    complete = false
                    log("${job.label}: the new store did not take the entries; trying again on the next start", null)
                }
            } catch (t: Throwable) {
                complete = false
                log("${job.label}: copying failed; trying again on the next start", t)
            }
        }
        if (!complete) return Outcome.RETRY_LATER
        return try {
            flag.set()
            Outcome.COPIED
        } catch (t: Throwable) {
            log("could not store the migration flag; the copy runs again on the next start", t)
            Outcome.RETRY_LATER
        }
    }

    /** The types SharedPreferences can hold. */
    private fun isStorable(value: Any?): Boolean = when (value) {
        is String, is Boolean, is Int, is Long, is Float -> true
        is Set<*> -> value.all { it is String }
        else -> false
    }

    // ---- Android ----------------------------------------------------------------------------------------

    /** Called first in [CosmotuneApplication.onCreate]. Never throws. */
    fun run(context: Context) {
        try {
            val app = context.applicationContext
            val log: (String, Throwable?) -> Unit = { message, error ->
                if (error == null) Log.i(TAG, "legacy data: $message") else Log.w(TAG, "legacy data: $message", error)
            }
            val outcome = copy(PrefsFlag(app.getSharedPreferences(FLAG_STORE, Context.MODE_PRIVATE)), jobs(app), log)
            if (outcome == Outcome.COPIED) {
                runCatching {
                    app.getSystemService(NotificationManager::class.java)?.deleteNotificationChannel(LEGACY_PLAYBACK_CHANNEL)
                }.onFailure { log("could not delete the old playback channel", it) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "legacy data: migration skipped", t)
        }
    }

    private fun jobs(app: Context): List<Job> =
        PLAIN_STORES.map { (old, new) ->
            Job(
                label = old,
                source = { if (exists(app, old)) PrefsStore(app.getSharedPreferences(old, Context.MODE_PRIVATE)) else null },
                target = { PrefsStore(app.getSharedPreferences(new, Context.MODE_PRIVATE)) },
            )
        } + ENCRYPTED_STORES.map { store ->
            Job(
                label = store.old,
                source = { oldEncrypted(app, store) },
                // The same opener the app uses, so the copy lands where AuthStore and SourceRegistry will look,
                // including the plain fallback on a device whose Keystore cannot be used.
                target = { PrefsStore(EncryptedPrefs.open(app, store.new, store.newPlain)) },
            )
        }

    /**
     * The old encrypted store and its plain fallback read together, plain values winning (while the fallback was in
     * use it was the only one written, as in [EncryptedPrefs]). Neither file is created, changed or deleted.
     */
    private fun oldEncrypted(app: Context, store: EncryptedStore): Store? {
        val hasEncrypted = exists(app, store.old)
        val hasPlain = exists(app, store.oldPlain)
        if (!hasEncrypted && !hasPlain) return null
        val values = LinkedHashMap<String, Any?>()
        if (hasEncrypted) {
            try {
                values += EncryptedSharedPreferences.create(
                    app,
                    store.old,
                    MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                ).all
            } catch (t: Throwable) {
                Log.w(TAG, "legacy data: ${store.old} cannot be decrypted on this device; only its plain fallback is copied", t)
            }
        }
        if (hasPlain) values += app.getSharedPreferences(store.oldPlain, Context.MODE_PRIVATE).all
        return object : Store {
            override fun read(): Map<String, Any?> = values
            override fun write(values: Map<String, Any?>) = false
        }
    }

    /** Whether a SharedPreferences file of that name was ever written; opening one that was not would create it. */
    private fun exists(app: Context, name: String): Boolean =
        File(File(app.applicationInfo.dataDir, "shared_prefs"), "$name.xml").isFile

    private class PrefsFlag(private val prefs: SharedPreferences) : Flag {
        override fun isSet() = prefs.getBoolean(FLAG_KEY, false)
        override fun set() {
            check(prefs.edit().putBoolean(FLAG_KEY, true).commit()) { "the flag did not reach the disk" }
        }
    }

    private class PrefsStore(private val prefs: SharedPreferences) : Store {
        override fun read(): Map<String, Any?> = prefs.all

        override fun write(values: Map<String, Any?>): Boolean {
            val edit = prefs.edit()
            for ((key, value) in values) {
                when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            return edit.commit()
        }
    }
}
