package com.cosmotune.reading.ui

import com.cosmotune.reading.data.*
import java.net.URI
import java.net.URLDecoder
import org.jsoup.Jsoup
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A publication never gets a native JS bridge, filesystem URL, or an external URL.
 * Only manifest resources are served, and active HTML/SVG and CSS URLs are filtered before WebKit sees them.
 */
internal class ReadingEpubContent(val document: ReadingDocument.Epub, val origin: String) {
    private val resourceTypes = setOf("text/css", "application/xhtml+xml", "text/html", "image/svg+xml",
        "image/png", "image/jpeg", "image/gif", "image/webp", "image/avif", "font/woff", "font/woff2", "font/ttf", "font/otf",
        "application/font-woff", "application/vnd.ms-opentype", "application/x-font-ttf", "application/x-font-opentype")
    fun href(url: String): String? = runCatching {
        if (!url.startsWith(origin)) return null
        val raw = url.removePrefix(origin).substringBefore('?')
        val path = URLDecoder.decode(raw.substringBefore('#').replace("+", "%2B"), "UTF-8")
        if (path !in document.resources) null else path + raw.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }
    }.getOrNull()
    fun url(href: String): String = origin + href.substringBefore('#').split('/').joinToString("/") {
        java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
    } + href.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }
    private fun local(base: String, raw: String): String? = runCatching {
        val clean = raw.trim().replace("\\", "/")
        if (clean.any { it.code < 32 } || clean.startsWith("//")) return null
        val resolved = URI("https://publication.invalid/" + base).resolve(clean.replace(" ", "%20")).normalize()
        if (resolved.scheme != "https" || resolved.host != "publication.invalid" || resolved.rawQuery != null) return null
        val path = URLDecoder.decode(resolved.rawPath.removePrefix("/").replace("+", "%2B"), "UTF-8")
        if (path !in document.resources) return null
        url(path + (resolved.rawFragment?.let { "#$it" } ?: ""))
    }.getOrNull()
    fun resource(href: String): Pair<String, ByteArray>? {
        val resource = document.resources[href.substringBefore('#')] ?: return null
        val type = resource.mediaType.lowercase().substringBefore(';')
        if (type !in resourceTypes || !resource.file.isFile || resource.file.length() > 32L * 1024 * 1024) return null
        return when (type) {
            "application/xhtml+xml", "text/html" -> "text/html; charset=utf-8" to html(resource.href, resource.file.readText()).toByteArray()
            "text/css" -> "text/css; charset=utf-8" to css(resource.href, resource.file.readText()).toByteArray()
            "image/svg+xml" -> "image/svg+xml" to html(resource.href, resource.file.readText(), true).toByteArray()
            else -> type to resource.file.readBytes()
        }
    }
    internal fun css(base: String, text: String): String {
        // Decode CSS escapes before looking for URL tokens; this also covers escaped @import / url.
        var clean = text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("\\\\([0-9a-fA-F]{1,6})\\s?")) { runCatching { String(Character.toChars(it.groupValues[1].toInt(16))) }.getOrDefault("") }
            .replace(Regex("\\\\(.)"), "$1")
        clean = clean.replace(Regex("(?i)@import\\s*(['\"])(.*?)\\1([^;]*);?")) { "@import url(\"${it.groupValues[2]}\")${it.groupValues[3]};" }
        clean = clean.replace(Regex("(?i)[^{};]*(?:image-set|cross-fade)\\s*\\([^;{}]*[;}]"), "")
        clean = clean.replace(Regex("(?i)(?:expression\\s*\\([^;]*|behavior\\s*:[^;]*|-moz-binding\\s*:[^;]*)"), "")
        return clean.replace(Regex("(?i)url\\s*\\(\\s*(['\"]?)(.*?)\\1\\s*\\)")) {
            local(base, it.groupValues[2])?.let { resolved -> "url(\"$resolved\")" } ?: "url(\"\")"
        }
    }
    internal fun html(base: String, text: String, svg: Boolean = false): String {
        val dom = if (svg) Jsoup.parse(text, "", org.jsoup.parser.Parser.xmlParser()) else Jsoup.parse(text)
        fun stripDeclarations(node: org.jsoup.nodes.Node) {
            node.childNodes().toList().forEach { child ->
                if (child is org.jsoup.nodes.XmlDeclaration || child is org.jsoup.nodes.DocumentType) child.remove()
                else stripDeclarations(child)
            }
        }
        stripDeclarations(dom)
        dom.select("script, iframe, frame, frameset, object, embed, applet, form, input, button, textarea, select, base, meta, foreignObject, animate, animateMotion, animateTransform, set, audio, video, source, track").remove()
        val allowed = setOf("id", "class", "title", "lang", "xml:lang", "dir", "style", "alt", "width", "height", "colspan", "rowspan", "scope", "role", "aria-label",
            "xmlns", "xmlns:xlink", "viewbox", "viewBox", "d", "x", "y", "x1", "x2", "y1", "y2", "cx", "cy", "r", "rx", "ry", "points", "transform", "fill", "stroke", "stroke-width",
            "fill-rule", "clip-rule", "opacity", "fill-opacity", "stroke-opacity", "clip-path", "preserveAspectRatio", "preserveaspectratio", "offset", "stop-color", "stop-opacity", "gradientUnits", "gradientTransform", "font-size", "font-family")
        dom.getAllElements().forEach { element ->
            element.attributes().asList().forEach { attribute ->
                val key = attribute.key.lowercase()
                when {
                    key == "style" -> element.attr(attribute.key, css(base, attribute.value))
                    key in setOf("href", "src", "xlink:href", "poster") -> {
                        val resolved = local(base, attribute.value)
                        if (resolved == null) element.removeAttr(attribute.key) else element.attr(attribute.key, resolved)
                    }
                    key == "rel" && element.normalName() == "link" -> if (attribute.value.lowercase() != "stylesheet") element.remove()
                    key in setOf("type", "media") && element.normalName() in setOf("link", "style") -> Unit
                    key in allowed -> if (attribute.value.contains("url", true) || attribute.value.contains('\\')) element.attr(attribute.key, css(base, attribute.value))
                    else -> element.removeAttr(attribute.key)
                }
            }
            if (element.normalName() == "style") element.text(css(base, element.data().ifEmpty { element.text() }))
            if (element.normalName() == "link" && element.attr("rel").lowercase() != "stylesheet") element.remove()
        }
        if (!svg) {
            dom.head().prepend("<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src $origin; style-src $origin 'unsafe-inline'; font-src $origin; script-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'\">")
            dom.head().appendElement("style").attr("id", "cosmotune-reader-style").text("html{overflow-wrap:break-word}body{max-width:56em;margin:0 auto;padding:24px;font-size:20px;line-height:1.5}img,svg{max-width:100%;height:auto}mark.cosmotune-match{background:#ffd85a;color:#211a00}")
        }
        return dom.outerHtml()
    }
}

internal object ReadingEpubScripts {
    private fun quote(value: String) = Json.encodeToString(value)
    fun preferences(value: ReadingPreferences, dark: Boolean): String {
        val theme = if (value.theme == ReadingTheme.SYSTEM) { if (dark) ReadingTheme.DARK else ReadingTheme.LIGHT } else value.theme
        val bg = when (theme) { ReadingTheme.DARK -> "#202124"; ReadingTheme.SEPIA -> "#f3ead7"; else -> "#ffffff" }
        val fg = when (theme) { ReadingTheme.DARK -> "#e7e4de"; ReadingTheme.SEPIA -> "#382e22"; else -> "#202124" }
        val family = when (value.fontFamily) {
            "publisher" -> ""
            "sans-serif", "monospace" -> "font-family:${value.fontFamily}!important;"
            else -> "font-family:serif!important;"
        }
        val css = "html{background:$bg!important;color:$fg!important;}body{background:$bg!important;color:$fg!important;${family}font-size:${20 * value.fontScale.coerceIn(.6f, 2.5f)}px!important;line-height:${value.lineHeight.coerceIn(1f, 2.5f)}!important;padding:${value.marginDp.coerceIn(8, 80)}px!important;}img,svg{max-width:100%;height:auto}a{color:inherit}mark.cosmotune-match{background:#ffd85a!important;color:#211a00!important}"
        return "(function(){var e=document.getElementById('cosmotune-reader-style');if(e)e.textContent=${quote(css)};})()"
    }
    fun restore(locator: ReadingLocator): String {
        val fragment = locator.resourceHref?.substringAfter('#', "").orEmpty()
        return if (fragment.isNotBlank()) "(function(){var e=document.getElementById(${quote(fragment)});if(e)e.scrollIntoView();})()"
        else "window.scrollTo(0,Math.max(0,document.documentElement.scrollHeight-window.innerHeight)*${locator.progression.coerceIn(0.0, 1.0)})"
    }
    val position = "JSON.stringify({progression:(window.scrollY/Math.max(1,document.documentElement.scrollHeight-window.innerHeight)),scrollOffset:window.scrollY})"
    fun search(query: String): String = """(function(){
        var old=document.querySelectorAll('mark.cosmotune-match');for(var i=0;i<old.length;i++){var p=old[i].parentNode;p.replaceChild(document.createTextNode(old[i].textContent),old[i]);p.normalize();}
        var q=${quote(query)}.toLocaleLowerCase();if(!q||!document.body)return 0;
        var walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false),nodes=[],n;while((n=walker.nextNode())){if(n.parentNode&&['STYLE','SCRIPT','MARK'].indexOf(n.parentNode.tagName)<0)nodes.push(n);}
        var count=0,first=null;for(var j=0;j<nodes.length;j++){n=nodes[j];var t=n.nodeValue,lower=t.toLocaleLowerCase(),index=lower.indexOf(q);if(index<0)continue;var f=document.createDocumentFragment(),start=0;while(index>=0&&count<2000){f.appendChild(document.createTextNode(t.substring(start,index)));var m=document.createElement('mark');m.className='cosmotune-match';m.textContent=t.substring(index,index+q.length);f.appendChild(m);if(!first)first=m;count++;start=index+q.length;index=lower.indexOf(q,start);}f.appendChild(document.createTextNode(t.substring(start)));n.parentNode.replaceChild(f,n);}if(first)first.scrollIntoView();return count;
        })()""".trimIndent()
}
