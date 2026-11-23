package com.cosmotune.music.data.innertube

import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Telling an upload that is gone from one that this client, this minute, was not given. */
class RemovedUploadTest {
    @Test fun aRemovedUploadIsAVerdict() {
        for (message in listOf(
            "Got error UNPLAYABLE: \"This video is not available\"",
            "Got error ERROR: \"Video unavailable\"",
            "Got error UNPLAYABLE: \"This video has been removed by the uploader\"",
            "Got error ERROR: \"This video is no longer available because the YouTube account associated with this video has been terminated.\"",
            "Got error UNPLAYABLE: \"This video is unavailable\"",
        )) {
            assertEquals(RemovedUpload.REASON, RemovedUpload.reason(ContentNotAvailableException(message)), message)
        }
    }

    @Test fun aClientOrSessionRefusalIsNot() {
        for (message in listOf(
            "Got error UNPLAYABLE: \"The following content is not available on this app.\"",
            "Got error LOGIN_REQUIRED: \"Sign in to confirm you're not a bot\"",
            "Got error UNPLAYABLE: \"This video is not available\" (Please sign in)",
            "Got error LOGIN_REQUIRED: \"This video is not available\"",
            "Could not get player response",
        )) {
            assertNull(RemovedUpload.reason(ContentNotAvailableException(message)), message)
        }
        // Only the content verdict type: a cut-off watch page saying the same words is a parse failure.
        assertNull(RemovedUpload.reason(ParsingException("Got error UNPLAYABLE: \"This video is not available\"")))
    }
}
