package com.cosmotune.manga.extensions

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceDataStore
import com.cosmotune.manga.data.MangaFileProtection
import com.cosmotune.manga.data.platformMangaFileProtection
import com.cosmotune.manga.data.ownerOnly
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** Extension preference values share the device-bound manga key, outside Android automatic backups. */
internal class ProtectedSourcePreferences(private val file: File, private val protection: MangaFileProtection,
    private val onFailure: () -> Unit = {}) : SharedPreferences {
    private val lock = Any()
    private val disk = Any()
    private val listeners = WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Unit>()
    private var version = 0L
    private var savedVersion = 0L
    private var blocked = false
    private var values: Map<String, Any> = try {
        if (!file.exists()) emptyMap() else {
            require(file.length() in 28..(MAX_BYTES + 64L * 1024))
            decode(protection.open(file.readBytes()))
        }
    } catch (_: Exception) { blocked = true; emptyMap() }

    override fun getAll(): MutableMap<String, *> = synchronized(lock) { values.mapValues { copy(it.value) }.toMutableMap() }
    private inline fun <reified T> value(key: String?, default: T): T = synchronized(lock) {
        val stored = values[key] ?: return@synchronized default
        copy(stored) as? T ?: throw ClassCastException("Source preference has a different type")
    }
    override fun getString(key: String?, defValue: String?): String? = value(key, defValue)
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = value(key, defValues)
    override fun getInt(key: String?, defValue: Int): Int = value(key, defValue)
    override fun getLong(key: String?, defValue: Long): Long = value(key, defValue)
    override fun getFloat(key: String?, defValue: Float): Float = value(key, defValue)
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = value(key, defValue)
    override fun contains(key: String?): Boolean = synchronized(lock) { values.containsKey(key) }
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) synchronized(lock) { listeners[listener] = Unit }
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        synchronized(lock) { listeners.remove(listener) }
    }
    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private var clear = false
        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            require(key != null && key.length <= 8_192) { "Invalid source preference key" }
            changes[key] = value?.let(::copy); return this
        }
        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: Set<String>?) = put(key, values)
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?) = put(key, null)
        override fun clear(): SharedPreferences.Editor { clear = true; return this }
        override fun commit(): Boolean = change(wait = true)
        override fun apply() { change(wait = false) }
        private fun change(wait: Boolean): Boolean {
            val changed = LinkedHashSet<String>()
            val snapshot: Pair<Long, Map<String, Any>> = synchronized(lock) {
                if (blocked) { onFailure(); return false }
                val next = if (clear) linkedMapOf() else values.toMutableMap()
                if (clear) changed.addAll(values.keys)
                changes.forEach { (key, value) ->
                    if (next[key] != value) changed += key
                    if (value == null) next.remove(key) else next[key] = copy(value)
                }
                // Validate the entire transaction before changing the in-memory view.
                try { encode(next) } catch (_: Exception) { onFailure(); return false }
                values = next; ++version to next.toMap()
            }
            val observers = synchronized(lock) { listeners.keys.toList() }
            if (changed.isNotEmpty() && observers.isNotEmpty()) main.post { changed.forEach { key ->
                observers.forEach { it.onSharedPreferenceChanged(this@ProtectedSourcePreferences, key) }
            } }
            val result = writer.submit<Boolean> { persist(snapshot.first, snapshot.second).also { if (!it) onFailure() } }
            return if (wait) runCatching { result.get() }.getOrDefault(false) else true
        }
    }

    private fun persist(revision: Long, snapshot: Map<String, Any>): Boolean = synchronized(disk) {
        if (revision < savedVersion) return@synchronized true
        runCatching {
            val bytes = protection.seal(encode(snapshot))
            val root = requireNotNull(file.parentFile)
            check(root.isDirectory || root.mkdirs()); root.ownerOnly()
            require(!Files.isSymbolicLink(file.toPath()))
            val temporary = File.createTempFile("prefs-", ".part", root)
            try {
                temporary.ownerOnly()
                temporary.outputStream().use { it.write(bytes); it.fd.sync() }
                try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                savedVersion = revision
            } finally { temporary.delete() }
        }.isSuccess
    }

    companion object {
        private const val MAX_BYTES = 1024 * 1024
        private val main = Handler(Looper.getMainLooper())
        private val writer = Executors.newSingleThreadExecutor { Thread(it, "manga-source-settings").apply { isDaemon = true } }
        private val opened = HashMap<String, ProtectedSourcePreferences>()
        @Synchronized fun open(context: Context, name: String): ProtectedSourcePreferences {
            require(name.length in 1..256)
            val hash = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val file = File(context.noBackupFilesDir, "manga-source-preferences/$hash.bin")
            return opened.getOrPut(file.absolutePath) { ProtectedSourcePreferences(file, platformMangaFileProtection(), onFailure = {
                main.post { android.widget.Toast.makeText(context, "Source settings could not be saved. Check storage and reopen settings.", android.widget.Toast.LENGTH_LONG).show() }
            }).also { prefs ->
                // This only migrates development/earlier source settings; production 1.0.3 had no extension reader.
                val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                val entries = legacy.all
                if (!prefs.blocked && entries.isNotEmpty()) {
                    val editor = prefs.edit()
                    entries.filterKeys { !prefs.contains(it) }.forEach { (key, value) -> when (value) {
                        is String -> editor.putString(key, value); is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value); is Long -> editor.putLong(key, value); is Float -> editor.putFloat(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    } }
                    if (editor.commit() && entries.all { (key, value) -> prefs.all[key] == value }) context.deleteSharedPreferences(name)
                }
            } }.also { check(!it.blocked) { "Source settings could not be opened. Their original file was preserved." } }
        }
        private fun copy(value: Any): Any = if (value is Set<*>) value.toSet() else value
        private fun encode(values: Map<String, Any>): ByteArray = ByteArrayOutputStream().use { out ->
            require(values.size <= 10_000)
            DataOutputStream(out).use { data ->
                data.writeInt(0x43535031); data.writeInt(values.size)
                values.forEach { (key, value) ->
                    data.writeUTF(key)
                    when (value) {
                        is String -> { data.writeByte(1); data.writeUTF(value) }
                        is Boolean -> { data.writeByte(2); data.writeBoolean(value) }
                        is Int -> { data.writeByte(3); data.writeInt(value) }
                        is Long -> { data.writeByte(4); data.writeLong(value) }
                        is Float -> { data.writeByte(5); data.writeFloat(value) }
                        is Set<*> -> { require(value.size <= 10_000); data.writeByte(6); data.writeInt(value.size); value.forEach { data.writeUTF(it as String); require(out.size() <= MAX_BYTES) } }
                        else -> error("Unsupported source preference")
                    }
                    require(out.size() <= MAX_BYTES)
                }
            }; out.toByteArray()
        }
        private fun decode(bytes: ByteArray): Map<String, Any> = DataInputStream(bytes.inputStream()).use { data ->
            require(bytes.size <= MAX_BYTES && data.readInt() == 0x43535031)
            val count = data.readInt().also { require(it in 0..10_000) }
            val values = linkedMapOf<String, Any>()
            repeat(count) {
                val key = data.readUTF().also { require(it.length <= 8_192 && it !in values) }
                values[key] = when (data.readUnsignedByte()) {
                    1 -> data.readUTF(); 2 -> data.readBoolean(); 3 -> data.readInt(); 4 -> data.readLong(); 5 -> data.readFloat()
                    6 -> { val size = data.readInt().also { require(it in 0..10_000) }; buildSet { repeat(size) { add(data.readUTF()) } } }
                    else -> error("Unsupported source preference")
                }
            }
            require(data.available() == 0); values
        }
    }
}

/** Both older Application-based and newer Context-based extension preference APIs use this facade. */
internal class SourcePreferenceApplication(private val original: Application) : Application() {
    init { attachBaseContext(original) }
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = ProtectedSourcePreferences.open(original, name)
}

internal class SourcePreferenceDataStore(private val prefs: SharedPreferences) : PreferenceDataStore() {
    override fun putString(key: String?, value: String?) { prefs.edit().putString(key, value).apply() }
    override fun putStringSet(key: String?, values: Set<String>?) { prefs.edit().putStringSet(key, values).apply() }
    override fun putInt(key: String?, value: Int) { prefs.edit().putInt(key, value).apply() }
    override fun putLong(key: String?, value: Long) { prefs.edit().putLong(key, value).apply() }
    override fun putFloat(key: String?, value: Float) { prefs.edit().putFloat(key, value).apply() }
    override fun putBoolean(key: String?, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun getString(key: String?, defValue: String?): String? = prefs.getString(key, defValue)
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = prefs.getStringSet(key, defValues)
    override fun getInt(key: String?, defValue: Int): Int = prefs.getInt(key, defValue)
    override fun getLong(key: String?, defValue: Long): Long = prefs.getLong(key, defValue)
    override fun getFloat(key: String?, defValue: Float): Float = prefs.getFloat(key, defValue)
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = prefs.getBoolean(key, defValue)
}
