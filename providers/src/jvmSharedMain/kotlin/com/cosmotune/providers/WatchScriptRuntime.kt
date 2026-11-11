package com.cosmotune.providers

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** CommonJS Watch scripts use the existing isolated VM, network permissions and cancellation limits. */
object WatchScriptRuntime {
    private val cryptoJs by lazy { requireNotNull(javaClass.getResourceAsStream("/providers/watch/crypto-js-4.2.0.js")).bufferedReader().use { it.readText() } }
    suspend fun streams(code: String, arguments: JsonObject, domains: List<String>, root: File,
        timeoutMs: Long = 15_000, settings: JsonObject = JsonObject(emptyMap())): JsonElement {
        val hash = MessageDigest.getInstance("SHA-256").digest(code.toByteArray()).joinToString("") { "%02x".format(it) }
        val manifest = ProviderManifest("watch-${hash.take(16)}", "1.0.0", type = listOf("metadata_provider"),
            permissions = ProviderPermissions(network = domains)).validate()
        val install = ProviderInstall(manifest, hash)
        val store = ProviderStore(root)
        val host = ProviderMetadataHost(install, store, network = ProviderNetwork(manifest.permissions,
            cookieJar = ProviderCookieJar.forProvider(install, store), publicInternet = domains.isEmpty()), mediaTools = null)
        val dom = WatchDomBridge()
        val config = buildJsonObject {
            put("permissions", Json.encodeToJsonElement(ProviderPermissions.serializer(), manifest.permissions.copy(network = domains.ifEmpty { listOf("public-https") })))
            put("settings", settings)
        }
        val script = """
            globalThis.crypto = {getRandomValues: function(view) {
                if (!ArrayBuffer.isView(view) || view.byteLength > 65536) throw Error('Invalid random byte request');
                var bytes = new Uint8Array(view.buffer, view.byteOffset, view.byteLength);
                for (var offset = 0; offset < bytes.length; offset += 4096) {
                    var count = Math.min(4096, bytes.length - offset), hex = utils.generateKey(count).hex;
                    for (var i = 0; i < count; i++) bytes[offset + i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
                }
                return view;
            }};
        """.trimIndent() + "\n" + cryptoJs + "\n" + """
            function domCall(method, args) {
                var result = JSON.parse(__cosmotuneHost.call('dom.' + method, JSON.stringify(args)));
                if (result.failure) throw Error(result.failure);
                return result.value;
            }
            function selection(value) {
                var node = {__dom: value.handle, length: value.length};
                node.find = function(query) { return selection(domCall('find', [node.__dom, String(query)])); };
                node.eq = function(index) { return selection(domCall('at', [node.__dom, index < 0 ? node.length + index : index])); };
                node.first = function() { return node.eq(0); }; node.last = function() { return node.eq(-1); };
                node.text = function() { return domCall('text', [node.__dom]); };
                node.html = function() { return domCall('html', [node.__dom]); };
                node.attr = function(name) { return domCall('attr', [node.__dom, String(name)]) || undefined; };
                node.parent = function() { return selection(domCall('parent', [node.__dom])); };
                node.children = function() { return selection(domCall('children', [node.__dom])); };
                node.remove = function() { domCall('remove', [node.__dom]); return node; };
                node.toArray = node.get = function(index) {
                    if (index != null) return node.eq(index);
                    var items = []; for (var i = 0; i < node.length; i++) items.push(node.eq(i)); return items;
                };
                node.each = function(callback) { node.toArray().forEach(function(item, index) { callback.call(item, index, item); }); return node; };
                node.map = function(callback) { var values = node.toArray().map(function(item, index) { return callback.call(item, index, item); }); return {get: function() { return values; }, toArray: function() { return values; }}; };
                return node;
            }
            var cheerio = {load: function(html) {
                var document = selection(domCall('load', [String(html)]));
                var query = function(value) { return value && value.__dom ? value : document.find(value); };
                query.root = function() { return document; }; query.html = document.html; return query;
            }};
            var axios = function(url, options) {
                if (typeof url === 'object') { options = url; url = options.url; }
                options = options || {};
                if (options.baseURL) url = String(new URL(url, options.baseURL));
                if (options.params) { var target = new URL(url); Object.keys(options.params).forEach(function(key) { target.searchParams.set(key, options.params[key]); }); url = String(target); }
                return fetch(url, {method: options.method || 'GET', headers: options.headers || {}, body: options.data}).then(function(response) {
                    return response.text().then(function(text) { var data = text; try { data = JSON.parse(text); } catch(e) {}
                        if (response.status >= 400) throw Error('Watch request failed (' + response.status + ')');
                        return {data: data, status: response.status, headers: response.headers};
                    });
                });
            };
            ['get','post','put','delete','head'].forEach(function(method) { axios[method] = function(url, data, options) {
                if (method === 'get' || method === 'delete' || method === 'head') { options = data || {}; data = undefined; }
                return axios(url, Object.assign({}, options || {}, {method: method.toUpperCase(), data: data}));
            }; });
            axios.create = function(defaults) { var instance = function(url, options) { return axios(url, Object.assign({}, defaults, options)); };
                ['get','post','put','delete','head'].forEach(function(method) { instance[method] = function(url, data, options) {
                    if (method === 'get' || method === 'delete' || method === 'head') return axios[method](url, Object.assign({}, defaults, data || {}));
                    return axios[method](url, data, Object.assign({}, defaults, options || {}));
                }; }); return instance;
            };
            var require = function(name) {
                if (name === 'cheerio') return cheerio;
                if (name === 'crypto-js') return CryptoJS;
                if (name === 'axios') return axios;
                if (name === 'url') return {URL: URL, URLSearchParams: URLSearchParams};
                throw Error('Unsupported Watch script module: ' + name);
            };
            var nativeFetch = globalThis.fetch;
            globalThis.getScraperSettings = globalThis.getSettings = function() { return settings; };
            globalThis.fetch = function(url, options) {
                var response = nativeFetch(url, options);
                if (!response.status || options && options.redirect === 'error' && response.status >= 300 && response.status < 400) throw Error('Watch network request failed');
                var text = response.text, json = response.json, bytes = response.arrayBuffer;
                var headers = response.headers || {};
                Object.defineProperty(headers, 'get', {value: function(name) { return headers[String(name).toLowerCase()] || null; }});
                Object.defineProperty(headers, 'has', {value: function(name) { return headers[String(name).toLowerCase()] !== undefined; }});
                Object.defineProperty(headers, 'forEach', {value: function(fn) { Object.keys(headers).forEach(function(key) { fn(headers[key], key); }); }});
                response.headers = headers;
                response.text = function() { return Promise.resolve(text()); };
                response.json = function() { return Promise.resolve().then(function() { return JSON.parse(text()); }); };
                response.arrayBuffer = function() { return Promise.resolve(bytes()); };
                return Promise.resolve(response);
            };
            var module = {exports: {}}, exports = module.exports;
            (function(module, exports) {
            $code
            })(module, exports);
            var watchPlugin = module.exports;
            if (typeof watchPlugin.getStreams !== 'function' && typeof globalThis.getStreams === 'function') watchPlugin = {getStreams: globalThis.getStreams};
            if (typeof watchPlugin === 'function') watchPlugin = {getStreams: watchPlugin};
            if (typeof watchPlugin.getStreams !== 'function') throw Error('Watch script must export getStreams');
            registerExtension({streams: function(args) {
                return watchPlugin.getStreams(args.tmdbId || args.id, args.mediaType, args.season, args.episode, args);
            }});
        """.trimIndent()
        try {
            return createProviderScriptSession(script, config, { method, args -> if (method.startsWith("dom.")) dom.call(method, args) else host.bridge(method, args) }, host::cancel).use { session ->
                Json.parseToJsonElement(session.invoke("streams", buildJsonArray { add(arguments) }, timeoutMs))
            }
        } finally { host.cancel() }
    }
}
