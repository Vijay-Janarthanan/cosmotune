package com.cosmotune.video.importers

import java.sql.Connection
import java.sql.DriverManager
import org.sqlite.SQLiteConfig

/**
 * `sqlite-jdbc` (the desktop already ships it) as [SqliteRows] (spec 8.2): the NewPipe database at [path], opened read-only
 * (a missing file is an error, not a new empty database). Integers read as `Long`, reals as `Double`, text as `String`, blobs
 * as `ByteArray`, NULL as null. [close] releases the file (`TempFileSqliteOpener` then deletes the temporary file).
 */
class JdbcSqliteRows(path: String) : SqliteRows {
    private val connection: Connection

    init {
        Class.forName("org.sqlite.JDBC")
        val config = SQLiteConfig()
        config.setReadOnly(true)
        connection = DriverManager.getConnection("jdbc:sqlite:$path", config.toProperties())
    }

    override fun query(sql: String): List<Map<String, Any?>> {
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                val meta = result.metaData
                val labels = Array(meta.columnCount) { meta.getColumnLabel(it + 1) }
                val rows = ArrayList<Map<String, Any?>>()
                while (result.next()) {
                    val row = LinkedHashMap<String, Any?>(labels.size * 2)
                    for (column in labels.indices) row[labels[column]] = normalised(result.getObject(column + 1))
                    rows.add(row)
                }
                return rows
            }
        }
    }

    override fun close() {
        connection.close()
    }

    private fun normalised(value: Any?): Any? = when (value) {
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Float -> value.toDouble()
        else -> value
    }
}
