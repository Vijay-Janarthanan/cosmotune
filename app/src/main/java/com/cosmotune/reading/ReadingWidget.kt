package com.cosmotune.reading

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import com.cosmotune.music.MainActivity
import com.cosmotune.music.R
import com.cosmotune.reading.ui.ReadingAndroidShelf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Reading now widget: the book read most lately (and not finished) with its cover, author and place, opening it in Read on a
 * tap. Read tells it when that changes ([ReadingAndroidShelf.onChange]); with no book yet it asks to open one.
 */
class ReadingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        work.launch { try { render(context, manager, ids) } finally { pending.finish() } }
    }

    companion object {
        /** The extra a tap carries: the book to open in Read. */
        const val EXTRA_BOOK = "com.cosmotune.reading.BOOK"
        private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Redraws every Reading now widget on the home screen (after the book or its place changed). */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, ReadingWidget::class.java)) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            work.launch { render(context.applicationContext, manager, ids) }
        }

        /** Hands a tap's book to Read (from the activity); true when the intent carried one. */
        fun consume(intent: Intent?): Boolean {
            val id = intent?.getStringExtra(EXTRA_BOOK) ?: return false
            intent.removeExtra(EXTRA_BOOK)
            ReadingAndroidShelf.open(id)
            return true
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val now = ReadingAndroidShelf.nowReading(context)
            val views = RemoteViews(context.packageName, R.layout.widget_reading)
            views.setTextViewText(R.id.widget_reading_title, now?.title ?: context.getString(R.string.widget_reading_empty))
            views.setTextViewText(R.id.widget_reading_line, now?.line.orEmpty())
            views.setViewVisibility(R.id.widget_reading_line, if (now?.line.isNullOrBlank()) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_reading_progress, if (now == null) View.GONE else View.VISIBLE)
            views.setProgressBar(R.id.widget_reading_progress, 1000, ((now?.fraction ?: 0f) * 1000).toInt(), false)
            val cover = now?.coverPath?.let { path ->
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    val sample = generateSequence(1) { it * 2 }.first { bounds.outHeight / it <= 320 }
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull()
            }
            if (cover != null) { views.setImageViewBitmap(R.id.widget_reading_cover, cover); views.setViewVisibility(R.id.widget_reading_cover, View.VISIBLE) }
            else views.setViewVisibility(R.id.widget_reading_cover, View.GONE)
            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_BOOK, now?.bookId ?: "")
            }
            views.setOnClickPendingIntent(R.id.widget_reading, PendingIntent.getActivity(context, 7_301, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
