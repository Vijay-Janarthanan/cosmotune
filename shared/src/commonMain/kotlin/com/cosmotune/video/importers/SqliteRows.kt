package com.cosmotune.video.importers

/**
 * A read-only view of a SQLite file, as small as the NewPipe import needs (spec 8.2): run a query, get its rows. Each row is
 * a map from the column label to its value: `Long` for integers, `Double` for reals, `String` for text, `ByteArray` for blobs,
 * null for NULL. The platform classes are `AndroidSqliteRows` (Android's own SQLite) and `JdbcSqliteRows` (`sqlite-jdbc`, the
 * desktop); both open the file read-only and release it in [close].
 */
interface SqliteRows {
    /** The rows of [sql], in the order the query gives. Throws when the query cannot run (a table the file lacks, say). */
    fun query(sql: String): List<Map<String, Any?>>

    /** Releases the file. */
    fun close()
}

/**
 * Opens the database held in [database] (the bytes of `newpipe.db`). The JVM implementation (`TempFileSqliteOpener`) writes
 * them to a temporary file, opens it through a platform [SqliteRows] and deletes the file when that is closed.
 */
fun interface SqliteOpener {
    /** The rows of the database, or throws when it cannot be opened. */
    fun open(database: ByteArray): SqliteRows
}
