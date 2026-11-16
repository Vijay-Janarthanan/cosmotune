package com.cosmotune.manga.data

import java.io.File
import java.net.URLClassLoader

internal actual val parserRuntime = "jvm"
internal actual val parserRuntimeSdk = Int.MAX_VALUE
internal actual fun parserClassLoader(file: File, classes: Set<String>, parent: ClassLoader): ClassLoader =
    object : URLClassLoader(arrayOf(file.toURI().toURL()), parent) {
        @Synchronized override fun loadClass(name: String, resolve: Boolean): Class<*> {
            val loaded = findLoadedClass(name) ?: if (name in classes) findClass(name) else parent.loadClass(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }
