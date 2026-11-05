package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.model.Song
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PreferenceLearningStatus(val ready: Boolean = false, val enabled: Boolean = true, val observations: Int = 0, val error: String? = null, val revision: Long = 0)

/** Device-local preferences. All disk work and learning run on one background worker. */
object LocalPreferenceLearning {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    @Volatile private var model: OnlinePreferenceModel? = null
    @Volatile private var explicit: ExplicitMusicFeedback? = null
    private var file: File? = null
    private val mutableStatus = MutableStateFlow(PreferenceLearningStatus())
    val status = mutableStatus.asStateFlow()
    private val playback = PreferencePlaybackSession { previous, song, liked ->
        val day = LocalDate.now().toEpochDay()
        val hour = LocalTime.now().hour
        scope.launch {
            val current = model ?: return@launch
            if (!current.enabled) return@launch
            current.learn(previous, song, liked, hour, day)
            persist(current)
        }
    }

    fun configure(directory: File) { scope.launch {
        val target = File(directory, "preferences.ctpref")
        if (file?.absolutePath == target.absolutePath && model != null) return@launch
        try {
            check(directory.isDirectory || directory.mkdirs())
            val current = if (target.exists()) {
                require(target.length() == OnlinePreferenceModel.STATE_BYTES.toLong()) { "Invalid preference state size" }
                OnlinePreferenceModel.decode(target.readBytes())
            } else OnlinePreferenceModel()
            val feedback = File(directory, "feedback.ctfeedback")
            val explicitState = if (feedback.exists()) {
                require(feedback.length() <= ExplicitMusicFeedback.MAX_BYTES)
                ExplicitMusicFeedback.decode(feedback.readBytes())
            } else ExplicitMusicFeedback()
            explicit = explicitState
            file = target; model = current; publish(current)
        } catch (_: Exception) {
            // Preserve unreadable state. Only an explicit Reset replaces it.
            file = target; model = null; explicit = null
            mutableStatus.value = PreferenceLearningStatus(ready = true, enabled = false,
                error = "Local preferences could not be opened. Reset them to start learning again.")
        }
    } }

    fun onSample(song: Song, durationMs: Long) = playback.sample(song, durationMs, System.nanoTime() / 1_000_000)
    fun onPaused() = playback.pause()
    fun onSkipped(videoId: String) = playback.skipped(videoId)

    /** Only explicit successful actions call this. A transport failure is never negative feedback. */
    fun onFeedback(song: Song, feedback: MusicFeedback) {
        if (song.isVideo || song.videoId.startsWith("podio:")) return
        scope.launch {
            val current = model ?: return@launch
            val history = explicit ?: return@launch
            if (!current.enabled || !history.update(song, feedback)) return@launch
            if (feedback != MusicFeedback.CLEAR) {
                val positive = feedback == MusicFeedback.LIKE
                val alreadyLearned = playback.explicitFeedback(song.videoId, positive)
                if (!alreadyLearned || !positive) repeat(3) { current.learn(null, song, positive, LocalTime.now().hour, LocalDate.now().toEpochDay()) }
            }
            persist(current)
        }
    }

    fun lessLikeThis(song: Song): Boolean = model?.enabled == true && explicit?.value(song) == MusicFeedback.LESS_LIKE_THIS
    fun explicitlyLiked(song: Song): Boolean = model?.enabled == true && explicit?.value(song) == MusicFeedback.LIKE

    internal fun adjustments(previous: Song?, songs: List<Song>, work: RankingWork? = null): FloatArray {
        val current = model ?: return FloatArray(songs.size)
        val day = LocalDate.now().toEpochDay()
        val hour = LocalTime.now().hour
        return FloatArray(songs.size) { current.adjustment(previous, songs[it], hour, day, work) }
    }

    fun setEnabled(enabled: Boolean) { scope.launch {
        val current = model ?: return@launch
        current.setEnabled(enabled); persist(current)
    } }

    fun reset() { scope.launch {
        val current = model ?: OnlinePreferenceModel().also { it.setEnabled(false); model = it }
        explicit = ExplicitMusicFeedback()
        current.reset(); persist(current)
    } }

    private fun publish(current: OnlinePreferenceModel, error: String? = null) {
        mutableStatus.value = PreferenceLearningStatus(true, current.enabled, current.observations, error, mutableStatus.value.revision + 1)
    }

    private fun persist(current: OnlinePreferenceModel) {
        val target = file ?: return
        var temporary: File? = null
        try {
            temporary = File.createTempFile("preferences-", ".part", target.parentFile)
            temporary.setReadable(false, false); temporary.setWritable(false, false)
            temporary.setReadable(true, true); temporary.setWritable(true, true)
            temporary.outputStream().use { output -> output.write(current.encode()); output.fd.sync() }
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            explicit?.let { feedback ->
                val data = feedback.encode()
                require(data.size <= ExplicitMusicFeedback.MAX_BYTES)
                val saved = File(target.parentFile, "feedback.ctfeedback")
                val staged = File.createTempFile("feedback-", ".part", target.parentFile)
                try {
                    staged.setReadable(false, false); staged.setWritable(false, false)
                    staged.setReadable(true, true); staged.setWritable(true, true)
                    staged.outputStream().use { it.write(data); it.fd.sync() }
                    try { Files.move(staged.toPath(), saved.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                    catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(staged.toPath(), saved.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                } finally { staged.delete() }
            }
            publish(current)
        } catch (_: Exception) { publish(current, "Local preferences could not be saved. Check available storage.") }
        finally { temporary?.delete() }
    }
}
