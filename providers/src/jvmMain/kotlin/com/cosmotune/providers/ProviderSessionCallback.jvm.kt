package com.cosmotune.providers

internal actual fun providerSessionCallback(): String {
    // Reserve a candidate only; prepareBrowserAuthentication binds before opening the browser.
    // A port claimed in between retains the existing explicit manual-callback recovery.
    val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        .use { it.localPort }
    return "http://127.0.0.1:$port/provider-auth/session-grant"
}
