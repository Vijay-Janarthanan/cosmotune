package com.cosmotune.providers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderMetadataHostTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun manifest(name: String) = ProviderManifest(name, "1.0.0", type = listOf("metadata_provider"),
        permissions = ProviderPermissions(storage = true))

    @Test fun credentialStorageIsScopedToItsProviderAndBadInputsNeverEchoSecrets() = runBlocking<Unit> {
        val store = ProviderStore(temporary.newFolder(), UnsealedProviderFiles)
        val first = ProviderMetadataHost(ProviderInstall(manifest("first"), "a".repeat(64)), store)
        val other = ProviderMetadataHost(ProviderInstall(manifest("other"), "b".repeat(64)), store)
        first.bridge("credentials.set", """["token","private-token"]""")
        val firstReply = Json.parseToJsonElement(first.bridge("credentials.get", """["token"]""")).jsonObject
        assertEquals("private-token", firstReply.getValue("value").jsonObject.getValue("value").jsonPrimitive.content)
        val otherReply = Json.parseToJsonElement(other.bridge("credentials.get", """["token"]""")).jsonObject
        assertFalse(otherReply.getValue("value").jsonObject.getValue("found").jsonPrimitive.boolean)
        assertFalse(first.bridge("http.request", """["not a URL/private-token",{}]""").contains("private-token"))
        assertFalse(first.bridge("credentials.set", "{ private-token").contains("private-token"))
    }

    @Test fun unicodeTextBuffersUrlQueriesAndHashesWorkInAnActualGuest() = runBlocking<Unit> {
        val installed = ProviderInstall(manifest("fixture"), "a".repeat(64))
        val host = ProviderMetadataHost(installed, ProviderStore(temporary.newFolder(), UnsealedProviderFiles))
        val config = buildJsonObject {
            putJsonObject("permissions") { putJsonArray("network") {}; put("storage", true); put("file", false) }
            putJsonObject("settings") {}
        }
        val code = """
            registerExtension({probe:function(){
              const bytes=new TextEncoder().encode('தமிழ்');
              const roundTrip=new TextDecoder().decode(bytes.buffer);
              const destination=new Uint8Array(3);
              const encoded=new TextEncoder().encodeInto('éa!',destination);
              const query=new URLSearchParams('q=hello+world&q=தமிழ்');
              storage.set('saved',roundTrip);
              return {roundTrip,read:encoded.read,written:encoded.written,second:query.getAll('q')[1],hash:utils.sha256('abc'),saved:storage.get('saved')};
            }});
        """.trimIndent()
        createProviderScriptSession(code, config, host::bridge, host.network::cancel).use { runtime ->
            val result = Json.parseToJsonElement(runtime.invoke("probe", JsonArray(emptyList()))).jsonObject
            assertEquals("தமிழ்", result.getValue("roundTrip").jsonPrimitive.content)
            assertEquals("தமிழ்", result.getValue("second").jsonPrimitive.content)
            assertEquals("தமிழ்", result.getValue("saved").jsonPrimitive.content)
            assertEquals(2, result.getValue("read").jsonPrimitive.int)
            assertEquals(3, result.getValue("written").jsonPrimitive.int)
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.getValue("hash").jsonPrimitive.content)
        }
    }

    @Test fun signedQueryUpdatesPreserveEscapesDuplicateValuesOrderAndFragmentsInTheGuest() = runBlocking<Unit> {
        val installed = ProviderInstall(manifest("fixture"), "a".repeat(64))
        val host = ProviderMetadataHost(installed, ProviderStore(temporary.newFolder(), UnsealedProviderFiles))
        val config = buildJsonObject { putJsonObject("permissions") { putJsonArray("network") {} } }
        val code = """
            registerExtension({probe:function(){
              const first=new URL('https://media.example/audio#player');
              first.searchParams.set('token','a+b/c =');
              const signed=new URL('https://media.example/audio?keep=one&sig=old&keep=two&sig=stale#player');
              signed.searchParams.set('sig','new+/=');
              return {first:first.toString(),second:signed.toString(),signature:signed.searchParams.get('sig'),
                repeated:signed.searchParams.getAll('keep'),query:signed.search};
            }});
        """.trimIndent()
        createProviderScriptSession(code, config, host::bridge, host.network::cancel).use { runtime ->
            val result = Json.parseToJsonElement(runtime.invoke("probe", JsonArray(emptyList()))).jsonObject
            assertEquals("https://media.example/audio?token=a%2Bb%2Fc+%3D#player", result.getValue("first").jsonPrimitive.content)
            assertEquals("https://media.example/audio?keep=one&sig=new%2B%2F%3D&keep=two#player", result.getValue("second").jsonPrimitive.content)
            assertEquals("new+/=", result.getValue("signature").jsonPrimitive.content)
            assertEquals(listOf("one", "two"), result.getValue("repeated").jsonArray.map { it.jsonPrimitive.content })
        }
    }
}
