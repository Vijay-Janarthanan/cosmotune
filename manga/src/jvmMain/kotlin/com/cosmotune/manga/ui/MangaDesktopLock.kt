package com.cosmotune.manga.ui

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The computer's own sign-in prompt Manga can ask for: Windows Hello, or Touch ID / the Mac's password. */
internal enum class MangaSystemLock(val label: String) { WINDOWS_HELLO("Windows Hello"), MAC("Touch ID or your Mac's password"), NONE("") }

internal object MangaDesktopLock {
    private val os = System.getProperty("os.name").orEmpty().lowercase()
    val isWindows = "windows" in os
    val isMac = "mac" in os || "darwin" in os

    /** Which prompt this computer has, found once (Windows Hello needs a PIN, face or fingerprint set up). */
    val system: MangaSystemLock by lazy {
        runCatching {
            when {
                isWindows -> if (WindowsHello.run(WindowsHello.CHECK).trim() == "Available") MangaSystemLock.WINDOWS_HELLO else MangaSystemLock.NONE
                isMac -> if (MacAuth.canAsk()) MangaSystemLock.MAC else MangaSystemLock.NONE
                else -> MangaSystemLock.NONE
            }
        }.getOrDefault(MangaSystemLock.NONE)
    }

    /** Shows the system's prompt; true once the listener passed it. */
    suspend fun ask(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            when (system) {
                MangaSystemLock.WINDOWS_HELLO -> WindowsHello.run(WindowsHello.VERIFY).trim() == "Verified"
                MangaSystemLock.MAC -> MacAuth.ask("open Manga")
                MangaSystemLock.NONE -> false
            }
        }.getOrDefault(false)
    }

    /** Where windows can be kept out of screenshots and screen sharing (Windows 10 2004 and later, macOS). */
    val canHideFromCapture: Boolean get() = isWindows || isMac

    /** Keeps [window] (Windows) or the app's windows (macOS) out of screenshots, recordings and screen sharing while [hide]. */
    fun hideFromCapture(window: Window?, hide: Boolean) {
        runCatching {
            when {
                isWindows && window != null -> {
                    val hwnd = WinDef.HWND(Native.getComponentPointer(window) ?: return)
                    // WDA_EXCLUDEFROMCAPTURE leaves the window out; older Windows only knows WDA_MONITOR, which shows it black.
                    if (!hide) User32Affinity.INSTANCE.SetWindowDisplayAffinity(hwnd, 0)
                    else if (!User32Affinity.INSTANCE.SetWindowDisplayAffinity(hwnd, 0x11)) User32Affinity.INSTANCE.SetWindowDisplayAffinity(hwnd, 0x1)
                }
                isMac -> MacAuth.setSharing(if (hide) 0L else 1L)
            }
        }
    }

    private interface User32Affinity : StdCallLibrary {
        fun SetWindowDisplayAffinity(hwnd: WinDef.HWND, affinity: Int): Boolean
        companion object { val INSTANCE: User32Affinity by lazy { Native.load("user32", User32Affinity::class.java) } }
    }

    /** Windows Hello through Windows PowerShell's WinRT bridge (UserConsentVerifier); no extra library. */
    private object WindowsHello {
        private const val HEAD = "Add-Type -AssemblyName System.Runtime.WindowsRuntime\n" +
            "\$m = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { \$_.Name -eq 'AsTask' -and \$_.GetParameters().Count -eq 1 -and \$_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1\n" +
            "\$v = [Windows.Security.Credentials.UI.UserConsentVerifier,Windows.Security.Credentials.UI,ContentType=WindowsRuntime]\n"
        const val CHECK = HEAD +
            "\$t = \$m.MakeGenericMethod([Windows.Security.Credentials.UI.UserConsentVerifierAvailability]).Invoke(\$null, @(\$v::CheckAvailabilityAsync()))\n" +
            "\$t.Wait(-1) | Out-Null; Write-Output \$t.Result"
        const val VERIFY = HEAD +
            "\$t = \$m.MakeGenericMethod([Windows.Security.Credentials.UI.UserConsentVerificationResult]).Invoke(\$null, @(\$v::RequestVerificationAsync('Unlock to open Manga')))\n" +
            "\$t.Wait(-1) | Out-Null; Write-Output \$t.Result"

        fun run(script: String): String {
            val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                .redirectErrorStream(true).start()
            process.outputStream.close()
            val out = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(3, TimeUnit.MINUTES)) { process.destroyForcibly(); return "" }
            return out.lineSequence().map(String::trim).lastOrNull { it.isNotEmpty() }.orEmpty()
        }
    }

    /** LocalAuthentication's LAContext and AppKit's window sharing, through the Objective-C runtime (JNA). */
    private object MacAuth {
        private interface ObjC : Library {
            fun objc_getClass(name: String): Pointer?
            fun sel_registerName(name: String): Pointer
        }
        private interface Dispatch : Library { fun dispatch_async_f(queue: Pointer, context: Pointer?, work: Callback) }
        private interface Work : Callback { fun invoke(context: Pointer?) }
        private interface Reply : Callback { fun invoke(block: Pointer?, success: Byte, error: Pointer?) }

        private const val DEVICE_OWNER = 2L // LAPolicyDeviceOwnerAuthentication: Touch ID, a watch, or the account password
        private val objc by lazy { Native.load("objc", ObjC::class.java) }
        private val send by lazy { NativeLibrary.getInstance("objc").getFunction("objc_msgSend") }
        private val system by lazy { NativeLibrary.getInstance("System") }
        private val dispatch by lazy { Native.load("System", Dispatch::class.java) }
        private val pending = java.util.Collections.synchronizedSet(HashSet<Any>())

        private fun sel(name: String) = objc.sel_registerName(name)
        private fun context(): Pointer? {
            NativeLibrary.getInstance("/System/Library/Frameworks/LocalAuthentication.framework/LocalAuthentication")
            val cls = objc.objc_getClass("LAContext") ?: return null
            val made = send.invokePointer(arrayOf(cls, sel("alloc"))) ?: return null
            return send.invokePointer(arrayOf(made, sel("init")))
        }

        fun canAsk(): Boolean {
            val ctx = context() ?: return false
            val can = (send.invoke(Byte::class.javaObjectType, arrayOf(ctx, sel("canEvaluatePolicy:error:"), DEVICE_OWNER, Pointer.NULL)) as Byte).toInt() != 0
            send.invoke(Void::class.java, arrayOf(ctx, sel("release")))
            return can
        }

        suspend fun ask(reason: String): Boolean {
            val ctx = context() ?: return false
            val done = CompletableDeferred<Boolean>()
            val reply = object : Reply { override fun invoke(block: Pointer?, success: Byte, error: Pointer?) { done.complete(success.toInt() != 0) } }
            // A global block literal (isa, flags, reserved, invoke, descriptor) around the callback; LocalAuthentication copies it.
            val descriptor = Memory(16).apply { setLong(0, 0); setLong(8, 32) }
            val block = Memory(32).apply {
                setPointer(0, system.getGlobalVariableAddress("_NSConcreteGlobalBlock"))
                setInt(8, 1 shl 28); setInt(12, 0)
                setPointer(16, com.sun.jna.CallbackReference.getFunctionPointer(reply))
                setPointer(24, descriptor)
            }
            pending.addAll(listOf(reply, block, descriptor))
            val text = send.invokePointer(arrayOf(objc.objc_getClass("NSString"), sel("stringWithUTF8String:"), reason))
            send.invoke(Void::class.java, arrayOf(ctx, sel("evaluatePolicy:localizedReason:reply:"), DEVICE_OWNER, text, block))
            return try { withTimeoutOrNull(180_000) { done.await() } ?: false } finally {
                pending.removeAll(setOf(reply, block, descriptor))
                send.invoke(Void::class.java, arrayOf(ctx, sel("release")))
            }
        }

        /** Sets every app window's sharingType (0 none, 1 read-only, the default) on AppKit's main thread. */
        fun setSharing(type: Long) {
            val work = object : Work {
                override fun invoke(context: Pointer?) {
                    runCatching {
                        val app = send.invokePointer(arrayOf(objc.objc_getClass("NSApplication"), sel("sharedApplication"))) ?: return
                        val windows = send.invokePointer(arrayOf(app, sel("windows"))) ?: return
                        val count = (send.invoke(Long::class.javaObjectType, arrayOf(windows, sel("count"))) as Long)
                        for (i in 0 until count) {
                            val window = send.invokePointer(arrayOf(windows, sel("objectAtIndex:"), i)) ?: continue
                            send.invoke(Void::class.java, arrayOf(window, sel("setSharingType:"), type))
                        }
                    }
                    pending.remove(this)
                }
            }
            pending.add(work)
            dispatch.dispatch_async_f(system.getGlobalVariableAddress("_dispatch_main_q"), null, work)
        }
    }
}
