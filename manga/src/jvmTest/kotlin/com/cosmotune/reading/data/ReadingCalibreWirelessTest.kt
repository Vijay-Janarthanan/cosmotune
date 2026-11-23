package com.cosmotune.reading.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

class ReadingCalibreWirelessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun calibresAnswerToAHelloNamesItsPort() {
        assertEquals(CalibreServer("DESKTOP-1", "192.168.1.5", 9090),
            ReadingCalibreWireless.parseReply("calibre wireless device client (on DESKTOP-1);9090,8080", "192.168.1.5"))
        assertNull(ReadingCalibreWireless.parseReply("something else", "192.168.1.5"))
    }

    @Test fun messagesAreFramedByTheirLength() {
        val framed = ReadingCalibreWireless.frame(0, buildJsonObject { put("a", 1) })
        assertEquals("""11[0,{"a":1}]""", framed.decodeToString())
        val (opcode, args) = assertNotNull(ReadingCalibreWireless.read(ByteArrayInputStream(framed)))
        assertEquals(0, opcode); assertEquals(1, args["a"]!!.jsonPrimitive.int)
        assertEquals("", ReadingCalibreWireless.passwordHash("", "x"))
        assertEquals(40, ReadingCalibreWireless.passwordHash("pw", "challenge").length)
    }

    @Test fun aBookCalibreSendsLandsInTheLibraryAndEveryQuestionIsAnswered() = runBlocking<Unit> {
        val calibre = ByteArrayOutputStream()
        fun say(opcode: Int, body: String) = calibre.write(ReadingCalibreWireless.frame(opcode, Json.parseToJsonElement(body).jsonObject))
        say(ReadingCalibreWireless.GET_INITIALIZATION_INFO, """{"serverProtocolVersion":1,"currentLibraryName":"Home books","passwordChallenge":"c1"}""")
        say(ReadingCalibreWireless.GET_DEVICE_INFORMATION, "{}")
        say(ReadingCalibreWireless.SET_CALIBRE_DEVICE_INFO, """{"device_name":"x"}""")
        say(ReadingCalibreWireless.FREE_SPACE, "{}")
        say(ReadingCalibreWireless.GET_BOOK_COUNT, """{"canStream":true}""")
        say(ReadingCalibreWireless.SEND_BOOKLISTS, """{"count":0}""")
        say(ReadingCalibreWireless.SEND_BOOK, """{"lpath":"Herbert, Frank/Dune - Frank Herbert.epub","length":5,"thisBook":0,"totalBooks":1,"wantsSendOkToSendbook":true}""")
        calibre.write("hello".toByteArray())
        say(ReadingCalibreWireless.NOOP, "{}")
        say(ReadingCalibreWireless.NOOP, """{"ejecting":true}""")
        val received = mutableListOf<Pair<String, String>>()
        val device = ReadingCalibreWireless("Phone", temporary.newFolder()) { name, file -> received += name to file.readText() }
        val replies = ByteArrayOutputStream()
        device.serve(ByteArrayInputStream(calibre.toByteArray()), replies, password = "secret")
        assertEquals(listOf("Dune - Frank Herbert.epub" to "hello"), received)
        val input = ByteArrayInputStream(replies.toByteArray())
        val answers = generateSequence { ReadingCalibreWireless.read(input) }.toList()
        assertTrue(answers.all { it.first == ReadingCalibreWireless.OK })
        assertEquals(8, answers.size, "init, device info, device info set, free space, count, ok to send, keep-alive, eject")
        assertEquals(ReadingCalibreWireless.passwordHash("secret", "c1"), answers[0].second["passwordHash"]!!.jsonPrimitive.content)
        assertTrue(answers[0].second["passwordHash"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals(0, answers[4].second["count"]!!.jsonPrimitive.int)
        assertEquals(ReadingCalibreWireless.Status.Connected("Home books", 1), device.status.value)
    }
}
