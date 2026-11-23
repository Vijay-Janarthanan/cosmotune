package com.cosmotune.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.cosmotune.music.MainActivity
import com.cosmotune.music.R
import com.cosmotune.music.data.lyrics.LyricWidgetState
import com.cosmotune.music.playback.PlayerDeepLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The lyrics widget (the idea from Gramophone, see NOTICE.md): the line being sung, large, with the next one under it in a
 * quieter ink and the song above them, on a translucent card like the 4×1's. A tap opens the player. The playback service calls
 * [show] whenever the line changes, and only while one of these is placed; the last state is kept so a redraw by the launcher
 * shows the same words.
 */
class LyricWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val state = last ?: LyricWidgetState.idle()
        for (id in ids) runCatching { manager.updateAppWidget(id, views(context, state)) }
        placed = null
    }

    override fun onDeleted(context: Context, ids: IntArray) { placed = null }
    override fun onEnabled(context: Context) { placed = null }
    override fun onDisabled(context: Context) { placed = null }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        @Volatile private var last: LyricWidgetState? = null
        /** Whether any is on a home screen; asked once and forgotten when one is added or removed. */
        @Volatile private var placed: Boolean? = null

        /** Shows [state] on every placed lyrics widget, if it differs from what they show. Cheap on the calling thread. */
        fun show(context: Context, state: LyricWidgetState) {
            if (state == last) return
            last = state
            val app = context.applicationContext
            scope.launch {
                val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return@launch
                if (placed == false) return@launch
                val ids = runCatching { manager.getAppWidgetIds(ComponentName(app, LyricWidget::class.java)) }.getOrNull() ?: IntArray(0)
                placed = ids.isNotEmpty()
                for (id in ids) runCatching { manager.updateAppWidget(id, views(app, state)) }
            }
        }

        private fun views(context: Context, state: LyricWidgetState): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_lyrics).apply {
                setTextViewText(R.id.widget_lyrics_song, state.song)
                setViewVisibility(R.id.widget_lyrics_song, if (state.song.isBlank()) View.GONE else View.VISIBLE)
                setTextViewText(R.id.widget_lyrics_line, state.line)
                setTextViewText(R.id.widget_lyrics_next, state.next)
                setViewVisibility(R.id.widget_lyrics_next, if (state.next.isBlank()) View.GONE else View.VISIBLE)
                setOnClickPendingIntent(R.id.widget_lyrics, openPlayer(context))
            }

        private fun openPlayer(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(PlayerDeepLink.EXTRA_OPEN_PLAYER, true)
            return PendingIntent.getActivity(context, REQUEST_OPEN, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private const val REQUEST_OPEN = 7_301
    }
}
