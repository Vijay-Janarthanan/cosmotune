package com.cosmotune.video.downloads.android

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * The foreground service (type `dataSync`) that keeps a download going when the screen is left, declared in this module's
 * manifest and not exported.
 *
 * Stub until the download engine (Y-24), which fills it: it is never started and binds to nothing.
 */
class VideoDownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
