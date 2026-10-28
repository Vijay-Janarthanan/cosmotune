package com.cosmotune.data

/**
 * A small CSV reader (RFC 4180) for the Takeout files (spec 2.7, 8.2): titles hold commas and quotes, so the Takeout
 * importers read by position through this reader and never split on commas.
 *
 * Handled: quoted fields with commas and line breaks inside, a doubled quote inside a quoted field, CRLF, CR and LF line
 * ends, a UTF-8 byte order mark at the start, and a last line with or without its end. A blank line in the middle is a record
 * with one empty field (the old playlist layout has one between its metadata block and its videos); blank lines at the end
 * are dropped. A quote in the middle of an unquoted field is kept as it is, and a quoted field that never closes runs to the
 * end of the text (a damaged file is read as far as it goes, not refused).
 */
object Csv {
    /** The records of [text], each a list of fields. */
    fun parse(text: String): List<List<String>> {
        val records = ArrayList<List<String>>()
        var fields = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = if (text.startsWith('\uFEFF')) 1 else 0
        val n = text.length

        fun endRecord() {
            fields.add(field.toString())
            field.clear()
            records.add(fields)
            fields = ArrayList()
        }

        while (i < n) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text[i + 1] == '"') {
                        field.append('"')
                        i += 2
                    } else {
                        inQuotes = false
                        i += 1
                    }
                } else {
                    field.append(c)
                    i += 1
                }
                continue
            }
            when (c) {
                '"' -> {
                    if (field.isEmpty()) inQuotes = true else field.append(c)
                    i += 1
                }
                ',' -> {
                    fields.add(field.toString())
                    field.clear()
                    i += 1
                }
                '\r' -> {
                    endRecord()
                    i += if (i + 1 < n && text[i + 1] == '\n') 2 else 1
                }
                '\n' -> {
                    endRecord()
                    i += 1
                }
                else -> {
                    field.append(c)
                    i += 1
                }
            }
        }
        if (fields.isNotEmpty() || field.isNotEmpty()) endRecord()

        while (records.isNotEmpty() && records.last().isBlankRecord()) records.removeAt(records.lastIndex)
        return records
    }
}

/** Whether the record is a blank line: nothing in it but empty fields. */
internal fun List<String>.isBlankRecord(): Boolean = all { it.isBlank() }
