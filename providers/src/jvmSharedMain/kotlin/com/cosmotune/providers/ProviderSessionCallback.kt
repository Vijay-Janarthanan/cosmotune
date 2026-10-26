package com.cosmotune.providers

/** Android supplies its app-owned scheme; desktop uses the existing exact-state loopback listener. */
internal expect fun providerSessionCallback(): String
