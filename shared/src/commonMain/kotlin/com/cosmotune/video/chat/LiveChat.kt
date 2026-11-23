package com.cosmotune.video.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * The chat of a live stream, and the replay of a finished one, as the watch page's Chat tab shows it. Pure: a [LiveChatSource]
 * says where the messages come from (the site's own live chat endpoints in the real app), [LiveChatFeed] polls it and keeps
 * the list a screen draws.
 */

/** Who wrote a message, as far as the chat marks it: the channel's owner, a moderator, a member, a verified account, or anyone. */
enum class ChatRole { OWNER, MODERATOR, MEMBER, VERIFIED, NONE }

/** A Super Chat or Super Sticker: what was paid, as the site writes it ("$5.00"), and the colour it is drawn in (ARGB). */
data class ChatPaid(val amount: String, val colorArgb: Long)

/**
 * One chat message. [offsetMs] is where in the video a replayed message was written (null in a live chat); [paid] is set on a
 * Super Chat. [text] is plain: emoji shortcuts are replaced by the emoji itself where the site gives one.
 */
data class ChatMessage(
    val id: String,
    val author: String,
    val authorPhotoUrl: String?,
    val text: String,
    val role: ChatRole = ChatRole.NONE,
    val paid: ChatPaid? = null,
    val offsetMs: Long? = null,
)

/** Where a chat starts: the first continuation, and whether it is the replay of a finished stream. */
data class ChatStart(val token: String, val replay: Boolean)

/**
 * One answer of the chat endpoint: the new [messages], the message pinned at the top now ([pinned]; [unpinned] when the pin
 * was taken down), ids of messages taken down, the next continuation and how long the site asks to wait before it ([nextDelayMs]).
 */
data class ChatBatch(
    val messages: List<ChatMessage>,
    val next: String?,
    val nextDelayMs: Long? = null,
    val pinned: ChatMessage? = null,
    val unpinned: Boolean = false,
    val deleted: Set<String> = emptySet(),
)

/** Where the messages come from. [start] answers null when the video has no chat (chat turned off, or not a stream). */
interface LiveChatSource {
    suspend fun start(videoId: String): ChatStart?

    /** The next messages of a live chat after [token]. */
    suspend fun live(token: String): ChatBatch

    /** The replayed messages after [token], from [offsetMs] into the video. */
    suspend fun replay(token: String, offsetMs: Long): ChatBatch
}

/** The chat as the screen draws it. [status] says whether it is loading, showing, missing or failed; [replay] is a finished stream's. */
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val pinned: ChatMessage? = null,
    val status: ChatStatus = ChatStatus.LOADING,
    val replay: Boolean = false,
)

enum class ChatStatus { LOADING, SHOWING, NONE, FAILED }

/**
 * Polls one video's chat. A live chat is asked again after the delay the site names (kept between [MIN_DELAY_MS] and
 * [MAX_DELAY_MS]); a replay is read ahead of [positionMs] and its messages are shown as playback reaches them; a seek back, or
 * forward past what was read, starts the replay again from there. At most [KEEP] messages are kept. Call [start] once and
 * [close] when the chat goes away; [retry] after a failure.
 */
class LiveChatFeed(
    private val source: LiveChatSource,
    private val videoId: String,
    private val scope: CoroutineScope,
    private val positionMs: () -> Long = { 0L },
) {
    private val mutable = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = mutable

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        mutable.update { it.copy(status = if (it.messages.isEmpty()) ChatStatus.LOADING else it.status) }
        job = scope.launch {
            try {
                val start = source.start(videoId)
                if (start == null) {
                    mutable.update { it.copy(status = ChatStatus.NONE) }
                    return@launch
                }
                mutable.update { it.copy(replay = start.replay) }
                if (start.replay) runReplay(start.token) else runLive(start.token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                mutable.update { it.copy(status = ChatStatus.FAILED) }
            }
        }
    }

    fun retry() {
        job?.cancel()
        job = null
        start()
    }

    fun close() {
        job?.cancel()
        job = null
    }

    private suspend fun CoroutineScope.runLive(first: String) {
        var token: String? = first
        while (isActive && token != null) {
            val batch = source.live(token)
            apply(batch.messages, batch)
            token = batch.next
            delay((batch.nextDelayMs ?: DEFAULT_DELAY_MS).coerceIn(MIN_DELAY_MS, MAX_DELAY_MS))
        }
    }

    private suspend fun CoroutineScope.runReplay(first: String) {
        val pending = ArrayDeque<ChatMessage>()
        var token: String? = null
        var readUntil = 0L
        var lastShown = 0L
        var restartAt: Long? = positionMs()
        var tick = 0L
        var fetchAfter = 0L
        mutable.update { it.copy(status = ChatStatus.SHOWING) }
        while (isActive) {
            val now = positionMs()
            if (restartAt == null && (now < lastShown - SEEK_SLACK_MS || token != null && now > readUntil + SEEK_SLACK_MS)) restartAt = now
            restartAt?.let { at ->
                pending.clear()
                mutable.update { it.copy(messages = emptyList(), pinned = null) }
                val batch = source.replay(first, at)
                pending.addSorted(batch.messages)
                token = batch.next
                readUntil = maxOf(at, pending.lastOrNull()?.offsetMs ?: at)
                restartAt = null
                fetchAfter = tick + FETCH_GAP_TICKS
            }
            val next = token
            if (next != null && readUntil < now + READ_AHEAD_MS && tick >= fetchAfter) {
                val batch = source.replay(next, maxOf(readUntil, now))
                pending.addSorted(batch.messages)
                token = batch.next
                readUntil = maxOf(readUntil, now, pending.lastOrNull()?.offsetMs ?: now)
                fetchAfter = tick + FETCH_GAP_TICKS
            }
            val due = ArrayList<ChatMessage>()
            while (pending.isNotEmpty() && (pending.first().offsetMs ?: 0L) <= now) due += pending.removeFirst()
            if (due.isNotEmpty()) apply(due, null)
            lastShown = now
            tick++
            delay(REPLAY_TICK_MS)
        }
    }

    private fun ArrayDeque<ChatMessage>.addSorted(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        val all = (this + messages).distinctBy { it.id }.sortedBy { it.offsetMs ?: 0L }
        clear()
        addAll(all)
    }

    private fun apply(messages: List<ChatMessage>, batch: ChatBatch?) {
        mutable.update { state ->
            val gone = batch?.deleted.orEmpty()
            val seen = state.messages.mapTo(HashSet()) { it.id }
            val merged = (state.messages + messages.filter { it.id !in seen }).filter { it.id !in gone }.takeLast(KEEP)
            val pinned = when {
                batch?.pinned != null -> batch.pinned
                batch?.unpinned == true -> null
                else -> state.pinned
            }
            state.copy(messages = merged, pinned = pinned, status = ChatStatus.SHOWING)
        }
    }

    companion object {
        const val KEEP = 250
        const val MIN_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 10_000L
        const val DEFAULT_DELAY_MS = 5_000L
        const val READ_AHEAD_MS = 20_000L
        const val REPLAY_TICK_MS = 500L
        const val SEEK_SLACK_MS = 15_000L
        const val FETCH_GAP_TICKS = 6L
    }
}
