package com.cosmotune.providers

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderAccessRequiredTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun packageBytes(answer: String): ByteArray = ByteArrayOutputStream().use { output ->
        val manifest = """{"name":"fixture","version":"1.0.0","type":["metadata_provider","download_provider"],"permissions":{"network":[]}}"""
        val script = """registerExtension({searchTracks:function(){return $answer;},getDownloadUrl:function(){return $answer;},getTrack:function(){return $answer;}});"""
        ZipOutputStream(output).use { zip -> listOf("manifest.json" to manifest, "index.js" to script).forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
        output.toByteArray()
    }

    @Test fun verificationFlagsAndProtocolCodesStayActionableAcrossSearchTrackAndStream() = runBlocking<Unit> {
        for (answer in listOf(
            "{ok:false,needsVerification:true,error:'VERIFY_REQUIRED',auth_url:''}",
            "{success:false,error_type:'verification_required'}",
            "{success:false,errorType:'auth_required'}",
            "{requires_verification:true}",
            "{verification_required:true}",
            "{success:false,error_code:'ACCESS_REQUIRED'}",
        )) {
            ProviderManager(ProviderStore(temporary.newFolder(), UnsealedProviderFiles)).use { manager ->
                manager.trust(manager.inspect { packageBytes(answer).inputStream() })
                assertFailsWith<ProviderAccessRequiredException>(answer) { manager.search("fixture", "Song") }
                assertFailsWith<ProviderAccessRequiredException>(answer) { manager.track("fixture", "track") }
                val failure = assertFailsWith<ProviderAccessRequiredException>(answer) { manager.stream("fixture", "track") }
                assertEquals("Provider sign-in or verification required", failure.message)
                assertEquals("Provider sign-in or verification required", manager.errors.value["fixture"])
            }
        }
    }

    @Test fun anActualApiFailureRemainsADiagnosticFailureRatherThanEmptySearchOrVerification() = runBlocking<Unit> {
        val answer = "{success:false,error_type:'api_error',error_message:'HTTP 404 at https://operator.invalid/private?grant=fixture-secret'}"
        ProviderManager(ProviderStore(temporary.newFolder(), UnsealedProviderFiles)).use { manager ->
            manager.trust(manager.inspect { packageBytes(answer).inputStream() })
            val failure = assertFailsWith<ProviderResolutionException> { manager.search("fixture", "Song") }
            assertEquals("api_error", failure.errorType)
            assertEquals(404, failure.httpStatus)
            assertFalse(failure.message.orEmpty().contains("fixture-secret"))
            assertFalse(failure.message.orEmpty().contains("operator.invalid"))
        }
    }
}
