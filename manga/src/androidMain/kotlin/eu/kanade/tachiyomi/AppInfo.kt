// Derived from Tsuki 89b8d9cf6bb53303f340505f46c2ad2c1c8ac5b7; GPL-3.0.
// See manga/third-party/TSUKI_SOURCE_API.json and LICENSE.tsuki.
package eu.kanade.tachiyomi

import android.app.Application
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Stub class for Mihon extensions that reference AppInfo.
 * Extensions may call these methods for User-Agent strings or version checks.
 *
 * @since extension-lib 1.3
 */
@Suppress("UNUSED")
object AppInfo {
    /**
     * Version code of the host application.
     */
    fun getVersionCode(): Int = Injekt.get<Application>().let { app -> @Suppress("DEPRECATION") app.packageManager.getPackageInfo(app.packageName, 0).versionCode }

    /**
     * Version name of the host application.
     */
    fun getVersionName(): String = Injekt.get<Application>().let { app -> app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty() }

    /**
     * Supported image MIME types by the reader.
     */
    fun getSupportedImageMimeTypes(): List<String> = buildList {
        addAll(listOf("image/jpeg", "image/png", "image/gif", "image/webp"))
        if (android.os.Build.VERSION.SDK_INT >= 28) add("image/heif")
        if (android.os.Build.VERSION.SDK_INT >= 31) add("image/avif")
    }
}
