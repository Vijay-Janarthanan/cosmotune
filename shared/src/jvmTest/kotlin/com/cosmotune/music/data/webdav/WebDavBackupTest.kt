package com.cosmotune.music.data.webdav

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Against a small loopback WebDAV server: MKCOL, PUT, PROPFIND and DELETE as Nextcloud answers them. */
class WebDavBackupTest {
    private val files = ConcurrentHashMap<String, ByteArray>()
    private val folders = ConcurrentHashMap.newKeySet<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = URLDecoder.decode(exchange.requestURI.rawPath, "UTF-8").trimEnd('/')
            val authorised = exchange.requestHeaders.getFirst("Authorization") != null
            val (code, body) = when {
                !authorised -> 401 to ""
                exchange.requestMethod == "MKCOL" -> if (folders.add(path)) 201 to "" else 405 to ""
                exchange.requestMethod == "PUT" -> { files[path] = exchange.requestBody.readBytes(); 201 to "" }
                exchange.requestMethod == "DELETE" -> if (files.remove(path) != null) 204 to "" else 404 to ""
                exchange.requestMethod == "PROPFIND" -> 207 to buildString {
                    append("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">")
                    append("<d:response><d:href>${exchange.requestURI.rawPath}</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>")
                    files.keys.filter { it.startsWith("$path/") }.forEach { file ->
                        val href = file.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                        append("<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response>")
                    }
                    append("</d:multistatus>")
                }
                else -> 405 to ""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
        }
        start()
    }
    private val root = "http://127.0.0.1:${server.address.port}/dav/music"

    @AfterTest fun stop() = server.stop(0)

    @Test fun eachBackupGoesInItsFolderAndOnlyTheNewestAreKept() = runBlocking<Unit> {
        files["/dav/music/Cosmotune backups/notes.txt"] = "mine".toByteArray()
        for (day in 1..7) {
            val name = "cosmotune-auto-backup-2026-11-0$day.json"
            WebDavBackup.upload(root, "me", "secret", name, "{\"day\":$day}".toByteArray()).getOrThrow()
        }
        val kept = files.keys.filter { it.startsWith("/dav/music/Cosmotune backups/") }.map { it.substringAfterLast('/') }.sorted()
        assertEquals(listOf("cosmotune-auto-backup-2026-11-03.json", "cosmotune-auto-backup-2026-11-04.json", "cosmotune-auto-backup-2026-11-05.json",
            "cosmotune-auto-backup-2026-11-06.json", "cosmotune-auto-backup-2026-11-07.json", "notes.txt"), kept)
        assertEquals("{\"day\":7}", files["/dav/music/Cosmotune backups/cosmotune-auto-backup-2026-11-07.json"]!!.decodeToString())
        assertTrue(WebDavBackup.upload(root, "", "", "x.json", ByteArray(1)).isFailure, "a refused sign-in is a failure")
        assertTrue(WebDavBackup.upload("", "me", "secret", "x.json", ByteArray(1)).isFailure)
    }
}
