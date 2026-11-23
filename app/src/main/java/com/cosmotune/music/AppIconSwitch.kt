package com.cosmotune.music

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.cosmotune.settings.AppIcon

/**
 * Puts the chosen app icon on the home screen: each [AppIcon] has its own launcher entry (an activity-alias of MainActivity in
 * the manifest), and only the chosen one is enabled. The aliases are named in this app's namespace, not its application id,
 * so the dev and release builds share them.
 */
object AppIconSwitch {

    fun apply(context: Context, icon: AppIcon) {
        val pm = context.packageManager
        // The new entry first, so there is never a moment with no launcher entry at all.
        val order = listOf(icon) + AppIcon.entries.filter { it != icon }
        for (each in order) {
            val component = ComponentName(context.packageName, "$NAMESPACE.${each.alias}")
            val wanted = if (each == icon) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            runCatching {
                if (pm.getComponentEnabledSetting(component) != wanted) {
                    pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
                }
            }
        }
    }

    private const val NAMESPACE = "com.cosmotune.music"
}
