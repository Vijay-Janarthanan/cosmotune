package com.cosmotune.video.importers

import kotlin.test.Test
import kotlin.test.assertEquals

class CsvTest {
    @Test
    fun readsPlainRecordsWithCrlfAndLf() {
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), Csv.parse("a,b,c\r\n1,2,3\r\n"))
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), Csv.parse("a,b,c\n1,2,3\n"))
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), Csv.parse("a,b\r1,2\r"), "a lone CR also ends a line")
    }

    @Test
    fun theLastLineNeedsNoEnd() {
        assertEquals(listOf(listOf("a", "b")), Csv.parse("a,b"))
        assertEquals(listOf(listOf("a"), listOf("b", "c")), Csv.parse("a\r\nb,c"))
    }

    @Test
    fun quotedFieldsKeepCommasQuotesAndLineBreaks() {
        assertEquals(listOf(listOf("a,b", "c")), Csv.parse("\"a,b\",c\r\n"))
        assertEquals(listOf(listOf("say \"hi\"", "x")), Csv.parse("\"say \"\"hi\"\"\",x\n"))
        assertEquals(listOf(listOf("line1\r\nline2", "x")), Csv.parse("\"line1\r\nline2\",x\r\n"))
        assertEquals(listOf(listOf("\"")), Csv.parse("\"\"\"\""), "a quoted field that holds one quote")
        assertEquals(listOf(listOf("", "x")), Csv.parse("\"\",x"), "an empty quoted field")
    }

    @Test
    fun emptyFieldsAreKept() {
        assertEquals(listOf(listOf("a", "", "c")), Csv.parse("a,,c\n"))
        assertEquals(listOf(listOf("", "b")), Csv.parse(",b\n"))
        assertEquals(listOf(listOf("a", "")), Csv.parse("a,\n"))
    }

    @Test
    fun aTrailingEmptyLineIsDropped() {
        assertEquals(listOf(listOf("a", "b")), Csv.parse("a,b\r\n\r\n"))
        assertEquals(listOf(listOf("a", "b")), Csv.parse("a,b\n\n\n"))
        assertEquals(emptyList(), Csv.parse(""))
        assertEquals(emptyList(), Csv.parse("\r\n"))
    }

    @Test
    fun aBlankLineInTheMiddleIsARecordWithOneEmptyField() {
        // the old playlist layout: metadata block, blank line, videos
        assertEquals(listOf(listOf("h1", "h2"), listOf("m1", "m2"), listOf(""), listOf("v1", "t1")), Csv.parse("h1,h2\r\nm1,m2\r\n\r\nv1,t1\r\n"))
    }

    @Test
    fun aByteOrderMarkIsNotPartOfTheFirstField() {
        assertEquals(listOf(listOf("Channel Id", "Channel Url")), Csv.parse("\uFEFFChannel Id,Channel Url\r\n"))
    }

    @Test
    fun aQuoteInsideAnUnquotedFieldIsLiteral() {
        assertEquals(listOf(listOf("ab\"c", "d")), Csv.parse("ab\"c,d"))
        assertEquals(listOf(listOf("5\" pipe", "x")), Csv.parse("5\" pipe,x"))
    }

    @Test
    fun anUnclosedQuoteRunsToTheEnd() {
        assertEquals(listOf(listOf("a", "b,c\r\nd")), Csv.parse("a,\"b,c\r\nd"))
    }

    @Test
    fun unicodeSurvives() {
        assertEquals(listOf(listOf("Tëst Chännel 中文", "🎵")), Csv.parse("Tëst Chännel 中文,🎵\n"))
    }

    @Test
    fun theTakeoutFilesRead() {
        val records = Csv.parse(ImportFixtures.text("takeout-subscriptions.csv"))
        assertEquals(5, records.size, "a header and four channels; the blank last line is dropped")
        assertEquals(listOf("Channel Id", "Channel Url", "Channel Title"), records[0])
        assertEquals(listOf(ImportFixtures.C2, "http://www.youtube.com/channel/${ImportFixtures.C2}", "Cooking \"Slow\", Daily"), records[3])
    }
}
