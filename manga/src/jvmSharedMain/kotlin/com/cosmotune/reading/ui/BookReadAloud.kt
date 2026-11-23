package com.cosmotune.reading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.ui.*
import com.cosmotune.reading.data.ReadingDocument
import com.cosmotune.reading.data.ReadingLocator
import com.cosmotune.reading.data.ReadingPreferences
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.orbit.OrbitReaderButton
import com.cosmotune.ui.orbit.OrbitReaderCard
import com.cosmotune.ui.orbit.orbitLook
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import androidx.compose.ui.text.withStyle
import kotlinx.serialization.json.*

/** A sentence of the open chapter: offsets into its text and the words to speak. */
internal data class SpokenSentence(val start: Int, val end: Int, val text: String)

/** What [ReadingEpubScripts.sentences] gave: the sentences and the first one on the screen. */
internal fun parseSentences(raw: String?): Pair<List<SpokenSentence>, Int> = runCatching {
    val root = Json.parseToJsonElement(raw ?: return emptyList<SpokenSentence>() to 0).jsonObject
    val list = root["list"]!!.jsonArray.map { item -> item.jsonArray.let { SpokenSentence(it[0].jsonPrimitive.int, it[1].jsonPrimitive.int, it[2].jsonPrimitive.content) } }
    list to (root["first"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, (list.size - 1).coerceAtLeast(0))
}.getOrDefault(emptyList<SpokenSentence>() to 0)

/** The sentence after [index] when reading aloud with the repeat from [a] to [b] (both set) looping back. */
internal fun nextSentence(index: Int, a: Int?, b: Int?): Int = if (a != null && b != null && index >= maxOf(a, b)) minOf(a, b) else index + 1

/** When the sleep timer stops reading: after some minutes, or at the end of the chapter. */
internal sealed interface SleepTimer {
    data class At(val ms: Long) : SleepTimer
    data object ChapterEnd : SleepTimer
}

/**
 * Reading aloud: the device's voice reads the open chapter sentence by sentence, marking each on the page and keeping it on the
 * screen, then turns to the next chapter. A repeat between two sentences (A and B), a sleep timer, the speed and the voice.
 */
internal class ReadAloud(private val speech: ReadingSpeech, private val page: ReadingPageControl, private val scope: CoroutineScope,
    private val document: ReadingDocument.Epub, private val preferences: () -> ReadingPreferences, private val navigate: (ReadingLocator) -> Unit,
    private val onError: (String) -> Unit) {
    var active by mutableStateOf(false); private set
    var playing by mutableStateOf(false); private set
    var index by mutableIntStateOf(0); private set
    var sentences by mutableStateOf(emptyList<SpokenSentence>()); private set
    var repeatA by mutableStateOf<Int?>(null); private set
    var repeatB by mutableStateOf<Int?>(null); private set
    var sleep by mutableStateOf<SleepTimer?>(null)
    private var href: String? = null
    private var job: Job? = null

    /** Starts reading at the selection's [startOffset] in the chapter, or at the first sentence on the screen. */
    fun start(startOffset: Int? = null) {
        active = true
        scope.launch {
            if (!load()) return@launch
            index = startOffset?.let { offset -> sentences.indexOfFirst { it.end > offset }.takeIf { it >= 0 } } ?: index
            play()
        }
    }

    private suspend fun load(): Boolean {
        val (list, first) = parseSentences(page.run(ReadingEpubScripts.sentences))
        href = page.href
        sentences = list
        index = first
        repeatA = null; repeatB = null
        return true
    }

    fun play() {
        if (playing) return
        playing = true
        job = scope.launch {
            try { loop() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { onError(failure.message ?: "Reading aloud stopped."); playing = false }
        }
    }

    fun pause() {
        playing = false
        job?.cancel(); job = null
        speech.stop()
    }

    fun stop() {
        pause()
        active = false
        scope.launch { page.run(ReadingEpubScripts.speaking(-1, -1)) }
        sentences = emptyList(); repeatA = null; repeatB = null; sleep = null
    }

    /** Moves by [delta] sentences, still reading if it was. */
    fun skip(delta: Int) {
        val was = playing
        pause()
        index = (index + delta).coerceIn(0, (sentences.size - 1).coerceAtLeast(0))
        scope.launch { sentences.getOrNull(index)?.let { page.run(ReadingEpubScripts.speaking(it.start, it.end)) } }
        if (was) play()
    }

    /** The repeat: the first tap sets A here, the second sets B, the third ends it. */
    fun repeat() {
        when {
            repeatA == null -> repeatA = index
            repeatB == null -> repeatB = index
            else -> { repeatA = null; repeatB = null }
        }
    }

    private suspend fun loop() {
        while (playing) {
            if (page.href != href || sentences.isEmpty()) {
                if (!load() || sentences.isEmpty()) { if (!nextChapter()) return else continue }
            }
            if (index >= sentences.size) {
                if (sleep == SleepTimer.ChapterEnd) { sleep = null; pause(); return }
                if (!nextChapter()) return
                continue
            }
            val sentence = sentences[index]
            withTimeoutOrNull(2_000) { page.run(ReadingEpubScripts.speaking(sentence.start, sentence.end)) }
            sentence.text.chunked(1_500).forEach { part -> speech.speak(part, preferences().voice, preferences().speechRate) }
            (sleep as? SleepTimer.At)?.let { if (System.currentTimeMillis() >= it.ms) { sleep = null; pause(); return } }
            index = nextSentence(index, repeatA, repeatB)
        }
    }

    /** Turns to the next chapter and waits for it to load; false (and stops) at the end of the book. */
    private suspend fun nextChapter(): Boolean {
        val current = document.spine.indexOfFirst { it.href == (href ?: page.href) }
        val next = document.spine.getOrNull(current + 1)
        if (next == null) { playing = false; active = false; page.run(ReadingEpubScripts.speaking(-1, -1)); return false }
        val before = page.loads
        navigate(ReadingLocator(resourceHref = next.href))
        withTimeoutOrNull(15_000) { snapshotFlow { page.loads to page.href }.first { (loads, at) -> loads > before && at == next.href } }
        delay(300)
        val (list, _) = parseSentences(page.run(ReadingEpubScripts.sentences))
        href = page.href; sentences = list; index = 0; repeatA = null; repeatB = null
        return true
    }
}

/** The reading-aloud card at the page's foot, in the book's colour: stop, the sentence line, previous, play or pause, next, repeat, timer and voice. */
@Composable
internal fun ReadAloudCard(aloud: ReadAloud, fill: Color, ink: Color, onTimer: () -> Unit, onVoice: () -> Unit) {
    val repeat = when { aloud.repeatB != null -> " · repeating"; aloud.repeatA != null -> " · repeat from here"; else -> "" }
    val timer = when (val sleep = aloud.sleep) {
        is SleepTimer.At -> " · stops in ${((sleep.ms - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1} min"
        SleepTimer.ChapterEnd -> " · stops at chapter end"
        null -> ""
    }
    OrbitReaderCard(
        label = (if (aloud.sentences.isEmpty()) "Reading aloud" else "Reading aloud · ${aloud.index + 1} of ${aloud.sentences.size}") + repeat + timer,
        progress = { if (aloud.sentences.size > 1) aloud.index / (aloud.sentences.size - 1f) else 0f },
        fill = fill, ink = ink,
        leading = { OrbitReaderButton(CosmoIcons.Close, "Stop reading aloud", ink, fill, aloud::stop, Modifier.testTag("books:aloud:stop")) },
        modifier = Modifier.testTag("books:aloud"),
    ) {
        OrbitReaderButton(Icons.Rounded.SkipPrevious, "Previous sentence", ink, fill, { aloud.skip(-1) }, Modifier.testTag("books:aloud:previous"))
        OrbitReaderButton(if (aloud.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (aloud.playing) "Pause" else "Read", ink, fill,
            { if (aloud.playing) aloud.pause() else aloud.play() }, Modifier.testTag("books:aloud:play"), on = true)
        OrbitReaderButton(Icons.Rounded.SkipNext, "Next sentence", ink, fill, { aloud.skip(1) }, Modifier.testTag("books:aloud:next"))
        Spacer(Modifier.weight(1f))
        OrbitReaderButton(CosmoIcons.Repeat, when { aloud.repeatA == null -> "Repeat from this sentence"; aloud.repeatB == null -> "Repeat to this sentence"; else -> "End the repeat" },
            ink, fill, aloud::repeat, Modifier.testTag("books:aloud:repeat"), on = aloud.repeatA != null)
        OrbitReaderButton(CosmoIcons.Timer, "Sleep timer", ink, fill, onTimer, Modifier.testTag("books:aloud:timer"), on = aloud.sleep != null)
        OrbitReaderButton(Icons.Rounded.RecordVoiceOver, "Voice and speed", ink, fill, onVoice, Modifier.testTag("books:aloud:voice"))
    }
}

/** The sleep timer's choices. */
@Composable
internal fun SleepTimerSheet(aloud: ReadAloud, onDismiss: () -> Unit) {
    KitSheet(onDismiss = onDismiss) {
        ReadSheetHeader("Sleep timer", onDone = onDismiss)
        ReadSheetRow("Off", null, { aloud.sleep = null; onDismiss() })
        listOf(15, 30, 45, 60).forEach { minutes ->
            ReadSheetRow("$minutes minutes", CosmoIcons.Timer, { aloud.sleep = SleepTimer.At(System.currentTimeMillis() + minutes * 60_000L); onDismiss() })
        }
        ReadSheetRow("End of this chapter", CosmoIcons.Timer, { aloud.sleep = SleepTimer.ChapterEnd; onDismiss() })
        Spacer(Modifier.height(16.dp))
    }
}

/** Speed and voice for reading aloud; the book's language's voices first. */
@Composable
internal fun VoiceSheet(speech: ReadingSpeech, preferences: ReadingPreferences, language: String, onChange: (ReadingPreferences) -> Unit, onDismiss: () -> Unit) {
    var voices by remember { mutableStateOf<List<ReadingVoiceInfo>?>(null) }
    LaunchedEffect(speech) { voices = speech.voices() }
    val code = language.substringBefore('-').lowercase()
    KitSheet(onDismiss = onDismiss) {
        ReadSheetHeader("Voice and speed", onDone = onDismiss)
        ReadSheetSection("Speed · ${"%.2g".format(preferences.speechRate)}×") {
            ReadChipRow(inset = 0.dp) {
                listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { rate ->
                    ReadChip("${"%.2g".format(rate)}×", preferences.speechRate == rate, { onChange(preferences.copy(speechRate = rate)) }, Modifier.testTag("books:aloud:speed:$rate"))
                }
            }
        }
        ReadOverline("Voice", inset = 20.dp)
        val ordered = voices.orEmpty().sortedBy { if (code.isNotBlank() && it.language.lowercase().startsWith(code)) 0 else 1 }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            item { ReadSheetRow("The device's own voice", if (preferences.voice.isBlank()) CosmoIcons.Check else null, { onChange(preferences.copy(voice = "")) }) }
            if (voices == null) item { Text("Finding voices…", style = ReadType.body(), color = orbitLook().dim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            else if (ordered.isEmpty()) item {
                Text("No other voices were found. Add voices in the system's speech settings.", style = ReadType.body(), color = orbitLook().dim,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            items(ordered, key = { it.id }) { voice ->
                ReadSheetRow("${voice.name} · ${languageName(voice.language)}", if (preferences.voice == voice.id) CosmoIcons.Check else null,
                    { onChange(preferences.copy(voice = voice.id)) })
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** The words of [sentences] one by one, for speed reading. */
internal fun speedWords(sentences: List<SpokenSentence>, from: Int = 0): List<String> =
    sentences.drop(from).flatMap { it.text.split(Regex("\\s+")) }.filter { it.isNotBlank() }

/** The letter a reader's eye rests on in [word] (a little left of the middle), as speed-reading apps mark it. */
internal fun focusLetter(word: String): Int = when (word.length) { 0, 1 -> 0; in 2..5 -> 1; in 6..9 -> 2; in 10..13 -> 3; else -> 4 }

/** Speed reading: the open chapter from the screen on, one word at a time at the chosen pace, its focus letter in the accent. */
@Composable
internal fun SpeedReadingSheet(page: ReadingPageControl, fill: Color, ink: Color, onDismiss: () -> Unit) {
    var words by remember { mutableStateOf<List<String>?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var pace by remember { mutableIntStateOf(300) }
    LaunchedEffect(Unit) { val (list, first) = parseSentences(page.run(ReadingEpubScripts.sentences)); words = speedWords(list, first) }
    LaunchedEffect(playing, pace, words) {
        val all = words ?: return@LaunchedEffect
        while (playing && index < all.size - 1) {
            val word = all[index]
            delay((60_000L / pace) * (if (word.last() in ".!?;:") 2 else 1))
            index++
        }
        if (index >= all.size - 1) playing = false
    }
    val look = orbitLook()
    KitSheet(onDismiss = onDismiss) {
        ReadSheetHeader("Speed reading", onDone = onDismiss, doneLabel = "Close")
        val all = words
        Box(Modifier.fillMaxWidth().height(140.dp).padding(horizontal = 20.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            val word = all?.getOrNull(index)
            if (word == null) Text(if (all == null) "Getting the chapter…" else "Nothing to read here.", style = ReadType.body(), color = look.dim)
            else {
                val focus = focusLetter(word).coerceAtMost(word.length - 1)
                Text(androidx.compose.ui.text.buildAnnotatedString {
                    append(word.substring(0, focus))
                    withStyle(androidx.compose.ui.text.SpanStyle(color = look.accent)) { append(word[focus]) }
                    append(word.substring(focus + 1))
                }, style = com.cosmotune.ui.orbit.orbitFonts().heroTitle, color = look.ink, maxLines = 1, modifier = Modifier.testTag("books:speed:word"))
            }
        }
        if (all != null && all.size > 1) com.cosmotune.ui.orbit.OrbitProgressLine({ index / (all.size - 1f) }, look.accent, Modifier.padding(horizontal = 20.dp),
            onSeek = { index = (it * (all.size - 1)).toInt() })
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OrbitReaderButton(Icons.Rounded.SkipPrevious, "Back ten words", look.ink, look.ground, { index = (index - 10).coerceAtLeast(0) })
            OrbitReaderButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Start", look.ink, look.ground, { playing = !playing },
                Modifier.testTag("books:speed:play"), on = true)
            OrbitReaderButton(Icons.Rounded.SkipNext, "On ten words", look.ink, look.ground, { index = (index + 10).coerceAtMost(((all?.size ?: 1) - 1).coerceAtLeast(0)) })
            Spacer(Modifier.weight(1f))
            Text("$pace words a minute", style = ReadType.caption(), color = look.dim)
        }
        ReadChipRow(Modifier.padding(bottom = 16.dp)) {
            listOf(200, 300, 400, 500, 650).forEach { value -> ReadChip("$value", pace == value, { pace = value }, Modifier.testTag("books:speed:$value")) }
        }
    }
}
