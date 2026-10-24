package com.cosmotune.manga.data

/** A mismatched extension API should fail that request, not terminate the app's coroutine host. */
internal inline fun <T> sourceApi(block: () -> T): T = try { block() }
catch (error: LinkageError) {
    throw IllegalStateException("This source needs an unavailable host API (${error.javaClass.simpleName}). Update or disable the extension.", error)
}
