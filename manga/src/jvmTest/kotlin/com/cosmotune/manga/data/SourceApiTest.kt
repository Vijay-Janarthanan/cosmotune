package com.cosmotune.manga.data

import org.junit.Test
import kotlin.test.*

class SourceApiTest {
    @Test fun aMissingExtensionApiBecomesARecoverableSourceFailure() {
        val missing = NoClassDefFoundError("a.missing.provider.Api")
        val failure = assertFailsWith<IllegalStateException> { sourceApi { throw missing } }
        assertSame(missing, failure.cause)
        assertTrue(failure.message.orEmpty().contains("Update or disable"))
        assertFalse(failure.message.orEmpty().contains("a.missing.provider.Api"))
    }
}
