package com.cosmotune.manga.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.manga.data.*

@Composable internal actual fun rememberMangaVerificationLauncher(onResult: (MangaVerificationRequest, Result<MangaBrowserProof>) -> Unit): (MangaVerificationRequest) -> Unit {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onResult)
    var pending by remember { mutableStateOf<MangaVerificationRequest?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
        val request = pending
        pending = null
        if (request != null) latest.value(request, runCatching {
            require(response.resultCode == Activity.RESULT_OK) { "Verification was cancelled." }
            val data = requireNotNull(response.data)
            require(data.getStringExtra(MangaVerificationActivity.NONCE) == request.nonce) { "The source verification changed." }
            MangaBrowserProof(requireNotNull(data.getStringExtra(MangaVerificationActivity.ADDRESS)),
                requireNotNull(data.getStringExtra(MangaVerificationActivity.COOKIES)),
                requireNotNull(data.getStringExtra(MangaVerificationActivity.USER_AGENT)))
        })
    }
    return { request ->
        if (Build.VERSION.SDK_INT < 28) latest.value(request, Result.failure(IllegalStateException("Isolated source verification needs Android 9 or newer. Choose another source on this device.")))
        else {
            pending = request
            launcher.launch(Intent(context, MangaVerificationActivity::class.java).putExtra(MangaVerificationActivity.ADDRESS, request.address)
                .putExtra(MangaVerificationActivity.NONCE, request.nonce).putExtra(MangaVerificationActivity.EXPIRES, request.expiresAt))
        }
    }
}
