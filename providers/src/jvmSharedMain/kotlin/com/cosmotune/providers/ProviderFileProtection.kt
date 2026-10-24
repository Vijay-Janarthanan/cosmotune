package com.cosmotune.providers

import java.io.File

interface ProviderFileProtection {
    fun seal(bytes: ByteArray): ByteArray
    fun open(bytes: ByteArray): ByteArray
}
internal object UnsealedProviderFiles : ProviderFileProtection {
    override fun seal(bytes: ByteArray) = bytes
    override fun open(bytes: ByteArray) = bytes
}
internal expect fun providerFileProtection(): ProviderFileProtection
internal expect fun File.ownerOnly()
