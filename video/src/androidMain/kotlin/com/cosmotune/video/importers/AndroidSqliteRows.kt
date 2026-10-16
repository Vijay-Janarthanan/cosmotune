package com.cosmotune.video.importers

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * Android's own SQLite as [SqliteRows] (spec 8.2): the NewPipe database at [path], opened read-only. Integers read as `Long`,
 * reals as `Double`, text as `String`, blobs as `ByteArray`, NULL as null. The file is not changed; [close] releases it
 * (`TempFileSqliteOpener` then deletes the temporary file).
 */
class AndroidSqliteRows(path: String) : SqliteRows {
    private val database: SQLiteDatabase = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)

    override fun query(sql: String): List<Map<String, Any?>> {
        database.rawQuery(sql, null).use { cursor ->
            val labels = Array(cursor.columnCount) { cursor.getColumnName(it) }
            val rows = ArrayList<Map<String, Any?>>(cursor.count)
            while (cursor.moveToNext()) {
                val row = LinkedHashMap<String, Any?>(labels.size * 2)
                for (column in labels.indices) row[labels[column]] = valueAt(cursor, column)
                rows.add(row)
            }
            return rows
        }
    }

    override fun close() {
        database.close()
    }

    private fun valueAt(cursor: Cursor, column: Int): Any? = when (cursor.getType(column)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column)
        else -> cursor.getString(column)
    }
}
