package com.cosmotune.reading.ui

import com.cosmotune.reading.data.*
import java.io.File
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
        if (path.startsWith(ReadingFonts.PREFIX)) return if (ReadingFonts.file(path.removePrefix(ReadingFonts.PREFIX)) != null) path else null
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
        if (href.startsWith(ReadingFonts.PREFIX)) return ReadingFonts.file(href.removePrefix(ReadingFonts.PREFIX))?.let { font ->
            (if (font.extension == "otf") "font/otf" else "font/${font.extension}") to font.readBytes()
        }
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
        val viewport = if (svg) null else dom.select("meta[name=viewport]").attr("content").let { content ->
            val width = Regex("width\\s*=\\s*(\\d+)").find(content)?.groupValues?.get(1)?.toIntOrNull()
            val height = Regex("height\\s*=\\s*(\\d+)").find(content)?.groupValues?.get(1)?.toIntOrNull()
            if (width != null && height != null && width in 100..10_000 && height in 100..10_000) "$width,$height" else null
        }
        dom.select("script, iframe, frame, frameset, object, embed, applet, form, input, button, textarea, select, base, meta, foreignObject, animate, animateMotion, animateTransform, set, audio, video, source, track").remove()
        val allowed = setOf("id", "class", "title", "lang", "xml:lang", "dir", "style", "alt", "width", "height", "colspan", "rowspan", "scope", "role", "aria-label",
            "xmlns", "xmlns:xlink", "viewbox", "viewBox", "d", "x", "y", "x1", "x2", "y1", "y2", "cx", "cy", "r", "rx", "ry", "points", "transform", "fill", "stroke", "stroke-width",
            "fill-rule", "clip-rule", "opacity", "fill-opacity", "stroke-opacity", "clip-path", "preserveAspectRatio", "preserveaspectratio", "offset", "stop-color", "stop-opacity", "gradientUnits", "gradientTransform", "font-size", "font-family",
            // Footnote marks and MathML's own presentation attributes (formulas render natively in WebView and WebKit).
            "epub:type", "mathvariant", "display", "displaystyle", "scriptlevel", "linethickness", "stretchy", "fence", "separator", "separators", "lspace", "rspace",
            "accent", "accentunder", "columnalign", "rowalign", "columnspan", "mathsize", "open", "close", "notation", "largeop", "movablelimits", "symmetric", "form")
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
            viewport?.let { dom.selectFirst("html")?.attr("data-cosmo-fixed", it) }
            val html = dom.selectFirst("html")
            if (html != null && !html.hasAttr("dir") && document.book.language.substringBefore('-').lowercase() in setOf("ar", "he", "fa", "ur", "yi", "ps", "dv", "ckb")) html.attr("dir", "rtl")
            dom.head().prepend("<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src $origin; style-src $origin 'unsafe-inline'; font-src $origin; script-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'\">")
            dom.head().appendElement("style").attr("id", "cosmotune-reader-style").text("html{overflow-wrap:break-word}body{max-width:56em;margin:0 auto;padding:24px;font-size:20px;line-height:1.5}img,svg{max-width:100%;height:auto}mark.cosmotune-match{background:#ffd85a;color:#211a00}")
        }
        return dom.outerHtml()
    }
}

internal object ReadingEpubScripts {
    private fun quote(value: String) = Json.encodeToString(value)
    /** The page and text colours of [value]'s theme ([dark] chooses for System). */
    fun colours(value: ReadingPreferences, dark: Boolean): Pair<String, String> {
        val theme = if (value.theme == ReadingTheme.SYSTEM) { if (dark) ReadingTheme.DARK else ReadingTheme.LIGHT } else value.theme
        return when (theme) {
            ReadingTheme.DARK -> "#202124" to "#e7e4de"; ReadingTheme.SEPIA -> "#f3ead7" to "#382e22"; ReadingTheme.GREY -> "#3b3c3f" to "#e4e1da"
            ReadingTheme.BLACK -> "#000000" to "#cfcac2"
            ReadingTheme.CUSTOM -> (value.pageColor.takeIf { it.matches(HEX) } ?: "#fdf6e3") to (value.textColor.takeIf { it.matches(HEX) } ?: "#2b2b2b")
            else -> "#ffffff" to "#202124"
        }
    }
    private val HEX = Regex("#[0-9a-fA-F]{6}")
    private val UNSAFE_CSS = listOf("url(", "@import", "expression", "behavior", "</", "javascript:")

    /** The reader's look laid over the chapter: colours, font, size, spacing, margins, typography, columns and the reader's own rules. */
    fun preferences(value: ReadingPreferences, dark: Boolean, origin: String = ""): String {
        val (bg, fg) = if (value.eInk) "#ffffff" to "#000000" else colours(value, dark)
        val text = "body p,body div,body span,body li,body blockquote,body td,body h1,body h2,body h3,body h4"
        val face = value.fontFamily.removePrefix("font:").takeIf { value.fontFamily.startsWith("font:") && ReadingFonts.file(it) != null }
        // The chosen font's whole family: its bold and italic files join the regular one.
        val family = ReadingFonts.known.firstOrNull { it.id == face }?.family
        val members = if (face == null) emptyList() else ReadingFonts.known.filter { family != null && it.family == family }.ifEmpty { listOf(ReadingFont(face, "", "")) }
        val fontFace = members.joinToString("") { font ->
            "@font-face{font-family:'CosmoReaderFont';src:url('" + origin + ReadingFonts.PREFIX + font.id + "');font-weight:${font.weight};font-style:${if (font.italic) "italic" else "normal"}}"
        }
        val familyName = when {
            face != null -> "'CosmoReaderFont',serif"
            value.fontFamily == "publisher" -> null
            value.fontFamily in setOf("sans-serif", "monospace") -> value.fontFamily
            else -> "serif"
        }
        val familyCss = familyName?.let { "font-family:$it!important;" }.orEmpty()
        val margin = value.marginDp.coerceIn(8, 80)
        val columns = value.columns == 2 && !value.scrollMode
        val extra = buildString {
            if (familyName != null) append("$text{font-family:inherit!important}")
            if (value.justify) append("body p,body li{text-align:justify!important}")
            if (value.hyphenate) append("body{-webkit-hyphens:auto!important;hyphens:auto!important}")
            if (value.paragraphSpacing >= 0f) append("body p{margin-top:0!important;margin-bottom:${value.paragraphSpacing.coerceIn(0f, 3f)}em!important}")
            if (value.paragraphIndent) append("body p{text-indent:1.5em!important}")
            if (value.hideCaptions) append("figcaption,.caption,.figcaption{display:none!important}")
            if (value.eInk) append("*{transition:none!important;animation:none!important;scroll-behavior:auto!important}a{text-decoration:underline!important}img{filter:grayscale(1) contrast(1.2)}")
            if (columns) append("html{height:100%!important;overflow-y:hidden!important}body{max-width:none!important;margin:0!important;box-sizing:border-box!important;height:100vh!important;-webkit-column-count:2;column-count:2;-webkit-column-gap:${margin * 2}px;column-gap:${margin * 2}px;column-fill:auto}")
            if (value.customCss.isNotBlank() && UNSAFE_CSS.none { value.customCss.contains(it, true) }) append(value.customCss)
        }
        val css = "html{background:$bg!important;color:$fg!important;}body{background:$bg!important;color:$fg!important;${familyCss}font-size:${20 * value.fontScale.coerceIn(.6f, 2.5f)}px!important;line-height:${value.lineHeight.coerceIn(1f, 2.5f)}!important;padding:${value.marginDp.coerceIn(8, 80)}px!important;}img,svg{max-width:100%;height:auto}a{color:inherit}mark.cosmotune-match{background:#ffd85a!important;color:#211a00!important}$HIGHLIGHT_CSS"
        val mark = if (columns) "document.documentElement.setAttribute('data-cosmo-columns','2');" else "document.documentElement.removeAttribute('data-cosmo-columns');"
        // A fixed-layout page keeps the publisher's look and is zoomed to fit the screen.
        return "(function(){var e=document.getElementById('cosmotune-reader-style'),f=document.documentElement.getAttribute('data-cosmo-fixed');" +
            "if(e)e.textContent=f?'':${quote(fontFace + css + extra)};$mark" +
            "if(f){var p=f.split(',');document.documentElement.style.zoom=Math.min(window.innerWidth/p[0],window.innerHeight/p[1]);}})()"
    }

    /** A screen on or back ([forward]): a page of two columns sideways, else nine tenths of the screen down. */
    fun page(forward: Boolean, slide: Boolean = false, curl: Boolean = false): String {
        val sign = if (forward) "" else "-"
        val how = if (slide && !curl) "smooth" else "auto"
        val move = "if(document.documentElement.getAttribute('data-cosmo-columns'))window.scrollBy({left:${sign}window.innerWidth,behavior:'$how'});else window.scrollBy({top:${sign}window.innerHeight*0.88,behavior:'$how'});"
        if (!curl) return "(function(){$move return '';})()"
        // Like paper: the screen swings away from its spine edge, the next one swings in from the other side, a shade following the fold.
        return "(function(){var b=document.body;if(!b||window.__cosmoTurning){$move return '';}window.__cosmoTurning=1;var f=$forward;" +
            "function at(){var cols=document.documentElement.getAttribute('data-cosmo-columns');return (cols?(window.scrollX+(f?0:window.innerWidth))+'px '+(window.innerHeight/2)+'px':(f?'0px ':'100% ')+(window.scrollY+window.innerHeight/2)+'px');}" +
            "var shade=document.createElement('div');shade.style.cssText='position:fixed;top:0;bottom:0;left:0;right:0;pointer-events:none;z-index:2147483645;opacity:0;transition:opacity .17s;background:linear-gradient('+(f?'to left':'to right')+',rgba(0,0,0,.28),rgba(0,0,0,0) 60%)';document.documentElement.appendChild(shade);" +
            "b.style.transformOrigin=at();b.style.transition='transform .17s ease-in';b.style.transform='perspective(1800px) rotateY('+(f?-62:62)+'deg)';shade.offsetWidth;shade.style.opacity='1';" +
            "setTimeout(function(){b.style.transition='none';$move " +
            "b.style.transformOrigin=(function(){var cols=document.documentElement.getAttribute('data-cosmo-columns');return cols?(window.scrollX+(f?window.innerWidth:0))+'px '+(window.innerHeight/2)+'px':(f?'100% ':'0px ')+(window.scrollY+window.innerHeight/2)+'px';})();" +
            "b.style.transform='perspective(1800px) rotateY('+(f?62:-62)+'deg)';b.offsetWidth;b.style.transition='transform .2s ease-out';b.style.transform='';shade.style.opacity='0';" +
            "setTimeout(function(){b.style.transition='';b.style.transform='';b.style.transformOrigin='';if(shade.parentNode)shade.parentNode.removeChild(shade);window.__cosmoTurning=0;},230);},175);return '';})()"
    }

    /**
     * The reading ruler: a band [lines] lines tall across the page, the page outside it dimmed; the round grip at its end edge drags
     * it up or down. [lines] 0 takes it away. It never takes a tap or a selection (only the grip does).
     */
    fun ruler(lines: Int): String = """(function(n){var r=document.getElementById('cosmo-ruler');
        if(n<=0){if(r)r.parentNode.removeChild(r);return '';}
        if(!r){r=document.createElement('div');r.id='cosmo-ruler';r.style.cssText='position:fixed;left:0;right:0;pointer-events:none;z-index:2147483646;box-shadow:0 0 0 100vmax rgba(0,0,0,.42);border-radius:4px';
        var g=document.createElement('div');g.id='cosmo-ruler-grip';g.setAttribute('aria-label','Move the reading ruler');
        g.style.cssText='position:absolute;right:6px;top:50%;width:36px;height:36px;margin-top:-18px;border-radius:18px;background:rgba(128,128,128,.7);pointer-events:auto;touch-action:none;cursor:ns-resize';
        r.appendChild(g);document.documentElement.appendChild(r);var drag=false;
        g.addEventListener('pointerdown',function(e){drag=true;if(g.setPointerCapture)g.setPointerCapture(e.pointerId);e.preventDefault();e.stopPropagation();});
        g.addEventListener('pointermove',function(e){if(!drag)return;window.__cosmoRulerAt=Math.min(.92,Math.max(.02,e.clientY/window.innerHeight));window.__cosmoRulerPlace();e.preventDefault();});
        g.addEventListener('pointerup',function(){drag=false;});g.addEventListener('pointercancel',function(){drag=false;});
        window.addEventListener('resize',function(){if(window.__cosmoRulerPlace)window.__cosmoRulerPlace();});}
        window.__cosmoRulerLines=n;window.__cosmoRulerPlace=function(){var lh=parseFloat(window.getComputedStyle(document.body).lineHeight)||28,h=lh*window.__cosmoRulerLines+8;
        r.style.height=h+'px';r.style.top=Math.max(0,Math.min(window.innerHeight-h,(window.__cosmoRulerAt||.33)*window.innerHeight-h/2))+'px';};
        window.__cosmoRulerPlace();return '';})($lines)""".trimIndent()

    /**
     * Paragraph mode: one paragraph at a time in full ink, the rest faded, the one in focus kept in the middle of the screen.
     * [step] 0 starts at the first paragraph on the screen, 1 and -1 move on and back. Gives "4/30", or "end" past the last one.
     */
    fun paragraph(step: Int): String = """(function(step){if(!document.body)return '';
        var list=[].slice.call(document.body.querySelectorAll('p,li,blockquote,h1,h2,h3,h4,h5,h6,dd,dt,pre,figcaption')).filter(function(e){return (e.textContent||'').trim()&&!e.querySelector('p,li,blockquote,pre');});
        if(!list.length)return '';
        if(!document.getElementById('cosmo-para-style')){var st=document.createElement('style');st.id='cosmo-para-style';
        st.textContent='.cosmo-para{opacity:.26;transition:opacity .2s}.cosmo-para.cosmo-para-on{opacity:1}';(document.head||document.documentElement).appendChild(st);}
        var now=document.querySelector('.cosmo-para-on'),i=now?list.indexOf(now):-1;
        if(step===0||i<0){i=0;for(var k=0;k<list.length;k++){if(list[k].getBoundingClientRect().bottom>window.innerHeight*0.08){i=k;break;}}}else i+=step;
        if(i>=list.length)return 'end';if(i<0)i=0;
        for(var j=0;j<list.length;j++){list[j].classList.add('cosmo-para');list[j].classList.remove('cosmo-para-on');}
        list[i].classList.add('cosmo-para-on');list[i].scrollIntoView({block:'center'});return (i+1)+'/'+list.length;})($step)""".trimIndent()
    const val paragraphOff = "(function(){var l=document.querySelectorAll('.cosmo-para');for(var i=0;i<l.length;i++){l[i].classList.remove('cosmo-para');l[i].classList.remove('cosmo-para-on');}return '';})()"

    /** Scrolls the chapter by itself at [pixelsPerSecond] (0 stops it); at its end it notes so for [autoScrollEnded]. */
    fun autoScroll(pixelsPerSecond: Int): String = "(function(v){if(window.__cosmoAutoId){cancelAnimationFrame(window.__cosmoAutoId);window.__cosmoAutoId=0;}window.__cosmoAutoEnd=0;if(v<=0)return '';" +
        "var last=0,acc=0;function step(t){if(last){acc+=v*(t-last)/1000;var d=Math.floor(acc);if(d>=1){window.scrollBy(0,d);acc-=d;}}last=t;" +
        "if(window.innerHeight+window.scrollY>=document.documentElement.scrollHeight-2){window.__cosmoAutoEnd=1;window.__cosmoAutoId=0;return;}window.__cosmoAutoId=requestAnimationFrame(step);}" +
        "window.__cosmoAutoId=requestAnimationFrame(step);return '';})($pixelsPerSecond)"
    const val autoScrollEnded = "(function(){var e=window.__cosmoAutoEnd?'1':'';window.__cosmoAutoEnd=0;return e;})()"
    fun restore(locator: ReadingLocator): String {
        val fragment = locator.resourceHref?.substringAfter('#', "").orEmpty()
        return if (fragment.isNotBlank()) "(function(){var e=document.getElementById(${quote(fragment)});if(e)e.scrollIntoView();})()"
        else "(function(){var d=document.documentElement,p=${locator.progression.coerceIn(0.0, 1.0)};if(d.getAttribute('data-cosmo-columns')){var w=Math.max(0,d.scrollWidth-window.innerWidth)*p;window.scrollTo(Math.round(w/window.innerWidth)*window.innerWidth,0);}else window.scrollTo(0,Math.max(0,d.scrollHeight-window.innerHeight)*p);})()"
    }
    val position = "JSON.stringify((function(){var d=document.documentElement;return d.getAttribute('data-cosmo-columns')?{progression:window.scrollX/Math.max(1,d.scrollWidth-window.innerWidth),scrollOffset:window.scrollX}:{progression:window.scrollY/Math.max(1,d.scrollHeight-window.innerHeight),scrollOffset:window.scrollY};})())"
    fun search(query: String): String = """(function(){
        var old=document.querySelectorAll('mark.cosmotune-match');for(var i=0;i<old.length;i++){var p=old[i].parentNode;p.replaceChild(document.createTextNode(old[i].textContent),old[i]);p.normalize();}
        var q=${quote(query)}.toLocaleLowerCase();if(!q||!document.body)return 0;
        var walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false),nodes=[],n;while((n=walker.nextNode())){if(n.parentNode&&['STYLE','SCRIPT'].indexOf(n.parentNode.tagName)<0&&!(n.parentNode.tagName==='MARK'&&n.parentNode.className==='cosmotune-match'))nodes.push(n);}
        var count=0,first=null;for(var j=0;j<nodes.length;j++){n=nodes[j];var t=n.nodeValue,lower=t.toLocaleLowerCase(),index=lower.indexOf(q);if(index<0)continue;var f=document.createDocumentFragment(),start=0;while(index>=0&&count<2000){f.appendChild(document.createTextNode(t.substring(start,index)));var m=document.createElement('mark');m.className='cosmotune-match';m.textContent=t.substring(index,index+q.length);f.appendChild(m);if(!first)first=m;count++;start=index+q.length;index=lower.indexOf(q,start);}f.appendChild(document.createTextNode(t.substring(start)));n.parentNode.replaceChild(f,n);}if(first)first.scrollIntoView();return count;
        })()""".trimIndent()

    /** The chapter's text nodes in reading order (not styles or scripts): the ground every offset below counts in. */
    private const val NODES = "function cosmoNodes(){var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false),a=[],n;while((n=w.nextNode())){var p=n.parentNode;if(p&&p.tagName!=='STYLE'&&p.tagName!=='SCRIPT')a.push(n);}return a;}"

    /**
     * The words selected on the page as {text,start,end,progression}, offsets into the chapter's text, or "" with none; also
     * {tapped} when a highlight was tapped since the last call (a click listener the first call puts on the page).
     */
    val selection = """(function(){$NODES
        if(!window.__cosmoTap){window.__cosmoTap=1;document.addEventListener('click',function(e){var t=e.target;if(!t||!t.closest)return;
        var a=t.closest('a[href]');if(a){var h=a.getAttribute('href')||'',i=h.indexOf('#'),hash=i>=0?h.substring(i+1):'';if(!hash)return;
        var same=a.href.split('#')[0]===location.href.split('#')[0],target=same?document.getElementById(decodeURIComponent(hash)):null;
        var kind=(a.getAttribute('epub:type')||'')+' '+(a.getAttribute('role')||''),aim=target?((target.getAttribute('epub:type')||'')+' '+(target.getAttribute('role')||'')+' '+(target.className||'')+' '+target.tagName):'';
        var word=a.textContent.trim(),note=/noteref/.test(kind)||/footnote|endnote|rearnote|note|ASIDE/.test(aim)||(word.length<=4&&(!!a.closest('sup')||/^\[?[*\u2020\u2021]?\d{0,3}\]?$/.test(word)));
        if(note){e.preventDefault();e.stopPropagation();window.__cosmoNote=target?{text:(target.closest('li,aside,p,div')||target).innerText.substring(0,6000)}:{href:a.href};}return;}
        if(t.tagName==='IMG'&&!t.closest('a'))window.__cosmoImage=t.src;
        var m=t.closest('mark.cosmotune-hl');if(m)window.__cosmoTapped=m.getAttribute('data-id');},true);}
        var tapped=window.__cosmoTapped||null,noted=window.__cosmoNote||null,image=window.__cosmoImage||null;window.__cosmoTapped=null;window.__cosmoNote=null;window.__cosmoImage=null;
        var extra={};if(tapped)extra.tapped=tapped;if(noted)extra.note=noted;if(image)extra.image=image;
        var s=window.getSelection();if(!s||s.rangeCount<1||s.isCollapsed||!s.toString().trim())return (tapped||noted||image)?JSON.stringify(extra):'';
        var r=s.getRangeAt(0),nodes=cosmoNodes(),off=0,start=-1,end=-1;
        for(var i=0;i<nodes.length;i++){var n=nodes[i],len=n.nodeValue.length;if(r.intersectsNode(n)){if(start<0)start=off+(n===r.startContainer?r.startOffset:0);end=off+(n===r.endContainer?r.endOffset:len);}off+=len;}
        if(start<0||end<=start)return '';
        var rect=r.getBoundingClientRect(),y=window.scrollY+rect.top;
        return JSON.stringify({tapped:tapped,note:noted,image:image,text:s.toString().substring(0,4000),start:start,end:end,progression:Math.min(1,Math.max(0,y/Math.max(1,document.documentElement.scrollHeight-window.innerHeight)))});
        })()""".trimIndent()

    const val clearSelection = "(function(){var s=window.getSelection();if(s)s.removeAllRanges();return '';})()"

    /** Lays [marks] (id, start, end, style) over the chapter, replacing the marks laid before. */
    fun highlights(marks: List<ReadingHighlight>): String =
        lay(marks.joinToString(",", "[", "]") { "{i:${quote(it.id)},s:${it.start},e:${it.end},c:${quote(it.style.name.lowercase())}}" }, "cosmotune-hl", false)

    /**
     * Marks the element [fragment] (its id) as the words a book's recorded narration speaks now, also with the book's own
     * [bookClass] when it names one, and keeps it on the screen; null takes the mark away.
     */
    fun narrating(fragment: String?, bookClass: String = ""): String {
        val id = fragment?.let { Json.encodeToString(it) } ?: "null"
        val extra = bookClass.takeIf { it.matches(Regex("[A-Za-z_-][A-Za-z0-9_-]{0,60}")) }?.let { ",'$it'" }.orEmpty()
        return """(function(id){var names=['cosmotune-narrating'$extra];
            if(!document.getElementById('cosmotune-narrating-style')){var s=document.createElement('style');s.id='cosmotune-narrating-style';
            s.textContent='.cosmotune-narrating{background:rgba(255,196,0,.32)!important;border-radius:4px;box-decoration-break:clone;-webkit-box-decoration-break:clone}';(document.head||document.documentElement).appendChild(s);}
            for(var n=0;n<names.length;n++){var old=document.querySelectorAll('.'+names[n]);for(var i=0;i<old.length;i++)old[i].classList.remove(names[n]);}
            if(id===null)return '0';var el=document.getElementById(id);if(!el)return '0';for(var k=0;k<names.length;k++)el.classList.add(names[k]);
            var r=el.getBoundingClientRect();if(r.top<0||r.bottom>window.innerHeight||r.left<0||r.right>window.innerWidth)el.scrollIntoView({block:'center',inline:'nearest'});
            return '1';})($id)""".trimIndent()
    }

    /** The id of the first element with an id that is on the screen now, to start narration from where the reader is. */
    val firstVisibleId = """(function(){var all=document.querySelectorAll('[id]');for(var i=0;i<all.length;i++){var r=all[i].getBoundingClientRect();
        if(r.bottom>0&&r.top<window.innerHeight&&r.right>0&&r.left<window.innerWidth&&r.height>0)return all[i].id;}return '';})()""".trimIndent()

    /** Marks the sentence being read aloud ([start] until [end]) and keeps it on the screen; a negative [start] takes the mark away. */
    fun speaking(start: Int, end: Int): String = lay(if (start < 0) "[]" else "[{i:'tts',s:$start,e:$end,c:'now'}]", "cosmotune-tts", true)

    private fun lay(list: String, kind: String, scroll: Boolean): String = """(function(list){$NODES
        var old=document.querySelectorAll('mark.$kind');for(var i=0;i<old.length;i++){var m=old[i],p=m.parentNode;while(m.firstChild)p.insertBefore(m.firstChild,m);p.removeChild(m);p.normalize();}
        var count=0,first=null;for(var j=0;j<list.length;j++){var h=list[j],nodes=cosmoNodes(),off=0;for(var k=0;k<nodes.length;k++){var n=nodes[k],len=n.nodeValue.length,s=Math.max(h.s,off),e=Math.min(h.e,off+len);
        if(s<e){var mid=n;if(s>off)mid=n.splitText(s-off);if(e-s<mid.nodeValue.length)mid.splitText(e-s);var mark=document.createElement('mark');mark.className='$kind c-'+h.c;mark.setAttribute('data-id',h.i);mid.parentNode.replaceChild(mark,mid);mark.appendChild(mid);if(!first)first=mark;count++;}
        off+=len;if(off>=h.e)break;}}
        if(${if (scroll) "true" else "false"}&&first){var r=first.getBoundingClientRect();if(r.top<0||r.bottom>window.innerHeight)first.scrollIntoView({block:'center'});}
        return String(count);})($list)""".trimIndent()

    /**
     * The chapter's sentences as {first, list: [[start, end, text]…]}, offsets into its text, each kept inside its paragraph;
     * [first] is the first one on the screen now.
     */
    val sentences = """(function(){$NODES
        var nodes=cosmoNodes(),blocks=[],off=0,cur=null;
        function block(n){var e=n.parentNode;while(e&&e!==document.body){var d=window.getComputedStyle(e).display;if(d!=='inline'&&d!=='inline-block'&&d!=='contents')return e;e=e.parentNode;}return document.body;}
        for(var i=0;i<nodes.length;i++){var n=nodes[i],b=block(n),len=n.nodeValue.length;if(!cur||cur.b!==b){cur={b:b,s:off,t:''};blocks.push(cur);}cur.t+=n.nodeValue;off+=len;}
        var out=[],first=-1;
        for(var j=0;j<blocks.length;j++){var bl=blocks[j],re=/[^.!?…。！？]+(?:[.!?…。！？]+["'”’)\]]*|$)/g,m,bottom=bl.b.getBoundingClientRect().bottom;
        while((m=re.exec(bl.t))!==null){if(!m[0]){re.lastIndex++;if(re.lastIndex>bl.t.length)break;continue;}var t=m[0],lead=t.length-t.replace(/^\s+/,'').length,body=t.replace(/\s+$/,'');
        if(body.length>lead){if(first<0&&bottom>0)first=out.length;out.push([bl.s+m.index+lead,bl.s+m.index+body.length,body.substring(lead)]);}}}
        return JSON.stringify({first:Math.max(0,first),list:out});})()""".trimIndent()

    /** Scrolls the highlight [id] to the middle of the page. */
    fun reveal(id: String) = "(function(){var m=document.querySelector('mark[data-id=\"'+${quote(id)}+'\"]');if(m)m.scrollIntoView({block:'center'});return m?'1':'';})()"

    private const val HIGHLIGHT_CSS = "mark.cosmotune-hl{color:inherit!important;background:rgba(255,204,64,.42)!important;border-radius:2px}mark.cosmotune-hl.c-green{background:rgba(110,200,120,.40)!important}mark.cosmotune-hl.c-blue{background:rgba(100,160,240,.40)!important}mark.cosmotune-hl.c-pink{background:rgba(240,120,180,.40)!important}mark.cosmotune-hl.c-underline{background:none!important;text-decoration:underline!important;text-decoration-color:#e0533d!important;text-decoration-thickness:2px!important;text-underline-offset:3px}mark.cosmotune-tts{color:inherit!important;background:rgba(120,170,255,.38)!important;border-radius:2px}"
}
