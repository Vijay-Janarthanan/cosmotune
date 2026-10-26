package com.cosmotune.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderAndroidRuntimeTest {
    @Test fun aLargeMetadataFetchCrossesQuickJsWithoutRelaxingTheRawNetworkCap() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.filesDir, "large-fetch-fixture-${java.util.UUID.randomUUID()}")
        val payload = "{\"value\":\"" + "x".repeat(1_550_000) + "\"}"
        val permissions = ProviderPermissions(network = listOf("localhost"), allowHttp = true)
        val install = ProviderInstall(ProviderManifest("large-fixture", "1.0.0", type = listOf("metadata_provider"), permissions = permissions),
            "0".repeat(64), allowLocalNetwork = true)
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val store = ProviderStore(root)
        val network = ProviderNetwork(permissions, allowLocalNetwork = true, baseClient = client)
        val host = ProviderMetadataHost(install, store, network)
        val config = buildJsonObject { putJsonObject("permissions") { putJsonArray("network") { add("localhost") } } }
        try {
            createProviderScriptSession("""registerExtension({probe:function(){var response=fetch('http://localhost/large');return response.json().value.length;}});""",
                config, host::bridge, host::cancel).use { runtime ->
                assertEquals(JsonPrimitive(1_550_000), Json.parseToJsonElement(runtime.invoke("probe", JsonArray(emptyList()))))
            }
            assertEquals(2 * 1024 * 1024, ProviderNetwork.MAX_METADATA_BYTES)
        } finally { host.cancel(); root.deleteRecursively() }
    }

    @Test fun operatorSessionUsesAndroidCryptoAndReopensOnlyFromProtectedProviderStorage() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.filesDir, "signed-session-fixture-${java.util.UUID.randomUUID()}")
        val config = buildJsonObject {
            put("namespace", "android-fixture"); put("baseUrl", "https://auth.example/v2")
            put("callbackUrl", "cosmotune://provider-auth/session-grant")
        }
        val install = ProviderInstall(ProviderManifest("signed-fixture", "1.0.0", type = listOf("metadata_provider"),
            permissions = ProviderPermissions(listOf("auth.example"), storage = true), signedSession = config), "0".repeat(64))
        val store = ProviderStore(root)
        val network = ProviderNetwork(install.manifest.permissions)
        try {
            store.update { it.copy(installed = listOf(install)) }
            val client = ProviderSignedSession(install, store, network)
            assertEquals(JsonPrimitive(false), client.call("status", JsonArray(emptyList())).jsonObject["authenticated"])
            store.update { old ->
                val auth = old.auth.getValue("signed-fixture")
                old.copy(auth = old.auth + ("signed-fixture" to JsonObject(auth + ("signedSession" to JsonObject(auth.getValue("signedSession").jsonObject + mapOf(
                    "session_id" to JsonPrimitive("android-operator-session"), "session_secret" to JsonPrimitive("android-operator-secret"),
                    "expires_at" to JsonPrimitive(java.time.Instant.ofEpochMilli(System.currentTimeMillis() + 600_000).toString())))))))
            }
            assertFalse(File(root, "providers.json").readBytes().toString(Charsets.ISO_8859_1).contains("android-operator-secret"))
            val reopened = ProviderStore(root)
            assertNull(reopened.recoveryMessage)
            val host = ProviderMetadataHost(install, reopened)
            val sdk = buildJsonObject { put("signedSession", true); putJsonObject("permissions") {
                putJsonArray("network") { add("auth.example") }; put("storage", true)
            } }
            createProviderScriptSession("registerExtension({probe:function(){return session.status();}});", sdk, host::bridge, host.network::cancel).use { runtime ->
                assertTrue("completeGrant" in runtime.methods)
                val result = runtime.invoke("probe", JsonArray(emptyList()))
                assertEquals(JsonPrimitive(true), Json.parseToJsonElement(result).jsonObject["authenticated"])
                assertFalse(result.contains("android-operator-secret"))
            }
            val vector = ProviderSignedSession.headers("TEST-HMAC-V1", "X-Test-", 300, "fixture@1", "extension",
                "session-fixture", "secret-fixture", "POST", okhttp3.HttpUrl.Builder().scheme("https").host("example.com").encodedPath("/v2/a%20b").build(),
                """{"hello":"world"}""", 1_792_800_000_123L, "0123456789abcdef0123456789abcdef")
            assertEquals("uKWKnHEIr-jd-aRrbvlk-sLZ_0Q-4NvnjjqORPgmeqs", vector["X-Test-Signature"])
            ProviderManager(reopened).use { it.clearAuthentication("signed-fixture") }
            assertEquals(setOf("_epoch"), reopened.state.value.auth.getValue("signed-fixture").keys)
        } finally { network.cancel(); root.deleteRecursively() }
    }

    @Test fun platformBlowfishSupportsThePatternedTransformCbcAndCtrWireFormats() {
        fun options(mode: String) = buildJsonObject {
            put("algorithm", "blowfish"); put("mode", mode); put("padding", "none")
            put("key", "0000000000000000"); put("iv", "0000000000000000")
            listOf("keyEncoding", "ivEncoding", "inputEncoding", "outputEncoding").forEach { put(it, "hex") }
        }
        for (mode in listOf("cbc", "ctr")) {
            val encrypted = ProviderCrypto.call("encryptBlockCipher", buildJsonArray { add("0000000000000000"); add(options(mode)) }) { }
            assertEquals(JsonPrimitive(true), encrypted.jsonObject["success"])
            assertEquals(JsonPrimitive("4ef997456198dd78"), encrypted.jsonObject["data"])
            val decrypted = ProviderCrypto.call("decryptBlockCipher", buildJsonArray { add("4ef997456198dd78"); add(options(mode)) }) { }
            assertEquals(JsonPrimitive("0000000000000000"), decrypted.jsonObject["data"])
        }
    }

    @Test fun trustedPackageUsesQuickJsScopedFilesSettingsAndKeystoreState() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.filesDir, "provider-fixture-${java.util.UUID.randomUUID()}")
        val manifest = """{"name":"fixture","version":"1.0.0","type":["metadata_provider"],"permissions":{"network":["auth.example"],"storage":true,"file":true},"settings":[{"key":"country","default":"IN"}]}"""
        val code = """
            registerExtension({
              initialize:function(){
                if(file.exists('../providers.json'))throw Error('Workspace escaped');
                var written=file.write('cache/value.txt','A private provider value');
                if(!written.success)throw Error('File host failed');
                storage.set('init',true);return true;
              },
              searchTracks:function(query){
                var read=file.read('cache/value.txt');
                if(!read.success||read.data!=='A private provider value')throw Error('File read failed');
                var encoder=new TextEncoder(), bytes=encoder.encode('தமிழ்');
                if(new TextDecoder().decode(bytes.buffer)!=='தமிழ்')throw Error('UTF-8 bridge failed');
                if(utils.md5('abc')!=='900150983cd24fb0d6963f7d28e17f72')throw Error('Hash bridge failed');
                var sealed=utils.encrypt('Tamil தமிழ்','fixture-key');
                if(!sealed.success||utils.decrypt(sealed.data,'fixture-key').data!=='Tamil தமிழ்')throw Error('GCM bridge failed');
                if(utils.decrypt('AAECAwQFBgcICQoLlzxZzrrsH4ApHq6R429YrNvyalaNUH3INMG3buz5mRDhckNKEw==','fixture-key').data!=='Tamil தமிழ்')throw Error('GCM wire format failed');
                var cipher={algorithm:'aes',key:'2b7e151628aed2a6abf7158809cf4f3c',keyEncoding:'hex',ivEncoding:'hex',inputEncoding:'hex',outputEncoding:'hex'};
                cipher.mode='ctr';cipher.iv='f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff';
                if(utils.encryptBlockCipher('6bc1bee22e409f96e93d7e117393172a',cipher).data!=='874d6191b620e3261bef6864990db6ce')throw Error('CTR vector failed');
                cipher.mode='cbc';cipher.iv='000102030405060708090a0b0c0d0e0f';
                if(utils.encryptBlockCipher('6bc1bee22e409f96e93d7e117393172a',cipher).data!=='7649abac8119b246cee98e9b12e9197d')throw Error('CBC vector failed');
                utils.setDownloadStatus('Preparing fixture audio');
                var plain='6bc1bee22e409f96e93d7e117393172a', expected='7649abac8119b246cee98e9b12e9197d';
                if(!file.writeBytes('cache/pattern.bin',plain.repeat(3),{encoding:'hex',truncate:true}).success)throw Error('Pattern source failed');
                cipher.operation='encrypt';cipher.segmentSize=16;cipher.transformEvery=2;
                var progressCalls=0;
                var transformed=file.transformPatternedBlocks('cache/pattern.bin','cache/patterned.bin',cipher,function(n,total){
                  progressCalls++;if(n<0||n>total)throw Error('Invalid native progress');
                });
                if(!transformed.success||!progressCalls)throw Error('File transform callback failed');
                if(file.readBytes('cache/patterned.bin',{encoding:'hex'}).data!==expected+plain+expected)throw Error('Pattern transform failed');
                if(auth.generatePKCE(96).verifier.length!==96)throw Error('PKCE verifier failed');
                if(!auth.setAuthCode({access_token:'fixture-provider-token',expires_at:Date.now()+600000})||!auth.isAuthenticated())throw Error('Provider auth failed');
                return [{id:'recording',name:query,artists:settings.country}];
              }
            });
        """.trimIndent()
        val archive = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip -> listOf("manifest.json" to manifest, "index.js" to code).forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            } }; output.toByteArray()
        }
        val store = ProviderStore(root)
        ProviderManager(store).use { manager ->
            val review = manager.inspect { archive.inputStream() }
            assertTrue(store.state.value.storage.isEmpty())
            val installed = manager.trust(review)
            val jar = ProviderCookieJar.forProvider(installed, store)
            val cookieUrl = okhttp3.HttpUrl.Builder().scheme("https").host("auth.example").build()
            jar.receive(listOf(requireNotNull(okhttp3.Cookie.parse(cookieUrl, "account=provider-cookie-secret; Max-Age=600; Secure; HttpOnly; Path=/"))), jar.request(cookieUrl).generation)
            assertTrue(store.state.value.storage.isEmpty())
            manager.saveSettings("fixture", buildJsonObject { put("country", "US") })
            val song = manager.search("fixture", "A recording").single().song()
            assertEquals("src:sflx-fixture::recording", song.videoId)
            assertEquals("US", song.artist)
            assertFalse(File(root, "providers.json").readBytes().toString(Charsets.UTF_8).contains("country"))
            val reopened = ProviderStore(root)
            assertNull(reopened.recoveryMessage)
            assertEquals(JsonPrimitive(true), reopened.state.value.storage["fixture"]?.get("init"))
            assertEquals(JsonPrimitive("fixture-provider-token"), reopened.state.value.auth["fixture"]?.get("access_token"))
            assertFalse(File(root, "providers.json").readBytes().toString(Charsets.UTF_8).contains("fixture-provider-token"))
            assertEquals(1, reopened.state.value.cookies["fixture"]?.size)
            assertFalse(File(root, "providers.json").readBytes().toString(Charsets.UTF_8).contains("provider-cookie-secret"))
            manager.setEnabled("fixture", false)
            var rejected = false
            try { manager.search("fixture", "Disabled recording") } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            manager.remove("fixture")
        }
    }
}
