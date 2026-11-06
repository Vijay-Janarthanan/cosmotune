package com.cosmotune.watch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-tap install: the app hosts hand every link opened from outside (an Android intent, a desktop URL handler or a
 * command-line argument, a pasted link) to [offer]; the Watch screens show [pending] as the Install sheet
 * ([WatchRepository.previewInstallLink]) or the pack import sheet ([WatchRepository.readPluginPack]) and call [handled].
 * Nothing is installed without the person pressing Install.
 */
object WatchLinks {
    sealed interface Link {
        val raw: String
        data class Install(override val raw: String) : Link
        data class Pack(override val raw: String) : Link
    }

    private val state = MutableStateFlow<Link?>(null)
    val pending: StateFlow<Link?> = state.asStateFlow()

    /** True when [link] is a Watch install or pack link and is now pending. */
    fun offer(link: String?): Boolean {
        val t = link?.trim()?.takeIf { it.isNotEmpty() && it.length <= 8_192 } ?: return false
        state.value = when {
            InstallLinks.isPackLink(t) -> Link.Pack(t)
            InstallLinks.isInstallLink(t) -> Link.Install(t)
            else -> return false
        }
        return true
    }

    fun handled() { state.value = null }
}
