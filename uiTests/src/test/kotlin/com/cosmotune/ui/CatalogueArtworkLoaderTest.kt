package com.cosmotune.ui

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.cosmotune.providers.ArtworkAccess
import com.cosmotune.providers.ProviderHub
import com.cosmotune.ui.providers.CatalogueArtworkFetcher
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/** Exercises the same Coil factory installed in both native application image loaders. */
class CatalogueArtworkLoaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun opaqueSourceCoverReachesTheNativeDecoder() = runBlocking<Unit> {
        val root = temporary.newFolder()
        ProviderHub.configure(root, force = true, cache = temporary.newFolder())
        withTimeout(10_000) { while (ProviderHub.state.value.loading) delay(20) }
        assertNotNull(ProviderHub.artwork)
        val bytes = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(21, 15, BufferedImage.TYPE_INT_RGB), "png", output); output.toByteArray()
        }
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val url = "http://127.0.0.1:${server.localPort}/cover.png?private-token"
            ProviderHub.setHttpArtworkSources(mapOf("fixture" to requireNotNull(ArtworkAccess.httpSource(url, true))))
            val response = async(Dispatchers.IO) {
                server.soTimeout = 10_000
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    require(reader.readLine().startsWith("GET /cover.png?private-token "))
                    while (!reader.readLine().isNullOrEmpty()) { /* Consume bounded fixture request headers. */ }
                    socket.getOutputStream().use { output ->
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(bytes); output.flush()
                    }
                }
            }
            val reference = requireNotNull(ProviderHub.artwork?.reference("addon:fixture", url))
            assertFalse(reference.contains("private-token"))
            val context = PlatformContext.INSTANCE
            val loader = ImageLoader.Builder(context).components { add(CatalogueArtworkFetcher.Factory()) }.build()
            try {
                val result = loader.execute(ImageRequest.Builder(context).data(reference).build())
                if (result is coil3.request.ErrorResult) throw result.throwable
                val loaded = assertIs<SuccessResult>(result)
                assertEquals(21, loaded.image.width); assertEquals(15, loaded.image.height)
                response.await()
            } finally { loader.shutdown(); response.cancel() }
        }
    }
}
