package com.cosmotune.video.importers

import java.io.File

/**
 * Opens the bytes of a `newpipe.db` as [SqliteRows] (spec 8.2: "extract to a temp file; through SqliteRows"). The platform's
 * reader needs a file: the bytes are written to a new temporary file in [directory] (the app's cache folder), [open] opens it
 * (`::AndroidSqliteRows` on Android, `::JdbcSqliteRows` on the desktop), and closing the rows closes the reader and deletes the
 * file together with the `-wal`, `-shm` and `-journal` files SQLite may have made beside it. If opening fails the file is
 * deleted at once.
 */
class TempFileSqliteOpener(private val directory: File, private val open: (path: String) -> SqliteRows) : SqliteOpener {
    override fun open(database: ByteArray): SqliteRows {
        directory.mkdirs()
        val file = File.createTempFile("import-", ".db", directory)
        try {
            file.writeBytes(database)
            return DeletingRows(open(file.path), file)
        } catch (e: Throwable) {
            deleteWithSiblings(file)
            throw e
        }
    }

    private class DeletingRows(private val rows: SqliteRows, private val file: File) : SqliteRows {
        override fun query(sql: String): List<Map<String, Any?>> = rows.query(sql)

        override fun close() {
            try {
                rows.close()
            } finally {
                deleteWithSiblings(file)
            }
        }
    }

    private companion object {
        fun deleteWithSiblings(file: File) {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) File(file.path + suffix).delete()
        }
    }
}
