package com.cosmotune.reading.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import kotlin.test.*

class ReadingNearbyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val loopback = InetAddress.getLoopbackAddress()

    private fun nearby(name: String, received: MutableList<Pair<String, String>> = mutableListOf()) =
        ReadingNearby(name, "Test", "desktop", OkHttpClient(), temporary.newFolder(), bindAddress = loopback, discover = false) { file, path ->
            received += file to path.readText()
        }

    @Test fun booksGoToTheOtherDeviceOnceItSaysYes() = runBlocking<Unit> {
        val received = mutableListOf<Pair<String, String>>()
        val phone = nearby("Phone", received).apply { start() }
        val computer = nearby("Computer").apply { start() }
        try {
            val target = NearbyDevice(phone.info(), "127.0.0.1")
            val book = File(temporary.root, "Story.epub").apply { writeText("an epub") }
            val photo = File(temporary.root, "photo.jpg").apply { writeText("not a book") }
            val answering = launch(Dispatchers.IO) {
                val offer = phone.offer.filterNotNull().first()
                assertEquals("Computer", offer.from.alias)
                assertEquals(listOf("Story.epub"), offer.books.map { it.fileName }, "only what Read opens is taken")
                phone.answer(offer, true)
            }
            val done = mutableListOf<Int>()
            withTimeout(20_000) { computer.send(target, listOf("Story.epub" to book, "photo.jpg" to photo)) { done += it } }
            answering.join()
            assertEquals(listOf("Story.epub" to "an epub"), received)
            assertEquals(listOf(1), done)
            assertTrue(phone.devices.value.any { it.name == "Computer" }, "a device that sends is listed too")
        } finally { phone.stop(); computer.stop() }
    }

    @Test fun noMeansNothingIsSent() = runBlocking<Unit> {
        val received = mutableListOf<Pair<String, String>>()
        val phone = nearby("Phone", received).apply { start() }
        val computer = nearby("Computer").apply { start() }
        try {
            val target = NearbyDevice(phone.info(), "127.0.0.1")
            val book = File(temporary.root, "Story.pdf").apply { writeText("a pdf") }
            launch(Dispatchers.IO) { phone.answer(phone.offer.filterNotNull().first(), false) }
            val failure = assertFailsWith<IllegalStateException> { withTimeout(20_000) { computer.send(target, listOf("Story.pdf" to book)) } }
            assertEquals("Phone said no.", failure.message)
            assertTrue(received.isEmpty())
        } finally { phone.stop(); computer.stop() }
    }

    @Test fun chunkedBodiesAndFileNamesAreReadSafely() {
        val chunked = "4\r\nWiki\r\n5;x=1\r\npedia\r\n0\r\n\r\n".toByteArray()
        assertEquals("Wikipedia", ReadingNearby.Companion.ChunkedInput(ByteArrayInputStream(chunked)).readBytes().decodeToString())
        assertEquals("book.epub", ReadingNearby.safeName("../../etc/book.epub"))
        assertEquals("a_b.pdf", ReadingNearby.safeName("a:b.pdf"))
        assertTrue(ReadingNearby.isBook("x.EPUB")); assertFalse(ReadingNearby.isBook("x.exe"))
        val head = ReadingNearby.readHead(ByteArrayInputStream("POST /x HTTP/1.1\r\nContent-Length: 2\r\n\r\nhi".toByteArray()))
        assertEquals(listOf("POST /x HTTP/1.1", "Content-Length: 2"), head)
    }
}
