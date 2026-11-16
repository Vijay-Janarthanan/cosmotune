package com.cosmotune.manga.data

import android.os.Build
import dalvik.system.DexClassLoader
import java.io.File

internal actual val parserRuntime = "android"
internal actual val parserRuntimeSdk get() = Build.VERSION.SDK_INT
internal actual fun parserClassLoader(file: File, classes: Set<String>, parent: ClassLoader): ClassLoader =
    object : DexClassLoader(file.absolutePath, file.parentFile!!.absolutePath, null, parent) {
        @Synchronized override fun loadClass(name: String, resolve: Boolean): Class<*> {
            val loaded = findLoadedClass(name) ?: if (name in classes) findClass(name) else parent.loadClass(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }
