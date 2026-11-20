package com.andharness.ru.data.tools.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure, stateless decoding/parsing helpers for the web engine plus the page-side JavaScript
 * snippets it injects. Free of Android types, so the readability/extraction rules stay trivially
 * testable on the JVM.
 */
internal object WebContentExtractor {

    /** evaluateJavascript returns a JS string literal; decode the JSON string wrapper. */
    fun decode(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return try {
            Json.parseToJsonElement(raw).jsonPrimitive.content
        } catch (e: Exception) {
            raw
        }
    }

    /** [raw] is the already-decoded inner JSON text `{"title":..,"text":..}` (see [decode]). */
    fun parseExtract(raw: String?): String? {
        val json = raw ?: return null
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            val title = (obj["title"] as? JsonPrimitive)?.content ?: ""
            val text = (obj["text"] as? JsonPrimitive)?.content ?: ""
            when {
                title.isBlank() && text.isBlank() -> null
                title.isBlank() -> text
                else -> "$title\n\n$text"
            }
        } catch (e: Exception) {
            null
        }
    }

    fun parseJsonArray(raw: String?): JsonArray? {
        val inner = decode(raw) ?: return null
        return try {
            Json.parseToJsonElement(inner).jsonArray
        } catch (e: Exception) {
            null
        }
    }

    fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    fun jsonStr(value: String): String =
        Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))

    const val MOBILE_CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; SM-A075F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    const val EXTRACT_JS =
        "(()=>{try{var t=document.title||'';var b=document.body;var x=b?b.innerText||'':'';return JSON.stringify({title:t,text:x});}catch(e){return JSON.stringify({title:'',text:'<error> '+e});}})()"

    /**
     * Readability-lite: clone the body, drop chrome (nav/scripts/forms/…), prefer the main content
     * container (article/main/#content) and return its text. Runs with `evaluateJavascript`:
     * https://developer.android.com/reference/android/webkit/WebView#evaluateJavascript(java.lang.String,%20android.webkit.ValueCallback%3Cjava.lang.String%3E)
     */
    const val READABLE_JS =
        "(()=>{try{" +
            "var b=document.body;if(!b)return JSON.stringify({title:document.title||'',text:''});" +
            "var c=b.cloneNode(true);" +
            "var junk=c.querySelectorAll('script,style,noscript,svg,canvas,iframe,template,nav,header,footer,aside,form,button,input,select,textarea,[role=navigation],[role=banner],[aria-hidden=true]');" +
            "for(var i=0;i<junk.length;i++){junk[i].parentNode&&junk[i].parentNode.removeChild(junk[i]);}" +
            "var m=c.querySelector('article,main,[role=main],#content,.content,.post,.article');" +
            "var t=((m||c).textContent||'').replace(/[ \\t\\u00a0]+/g,' ').replace(/\\n{3,}/g,'\\n\\n').trim();" +
            "return JSON.stringify({title:document.title||'',text:t});" +
            "}catch(e){return JSON.stringify({title:'',text:''});}})()"

    /**
     * Elements considered by the `web_click` by-text pass: real interactive controls, label/aria
     * surfaces, and generic containers (`div`/`span`/`li`) that may hold the visible text directly.
     * https://developer.mozilla.org/docs/Web/API/Document/querySelectorAll
     */
    const val CLICK_BY_TEXT_SELECTOR =
        "a,button,input,select,option,summary,label,[aria-label]," +
            "[role=button],[role=link],[role=menuitem],[role=tab],div,span,li"

    /** Elements that can receive a click, used to resolve a matched node's click target. */
    private const val CLICKABLE_SELECTOR =
        "a,button,[role=button],[role=link],[role=menuitem],[role=tab],label,input,select,option," +
            "summary,[aria-label],[onclick]"

    /**
     * Page-side body for `web_click`: click by CSS [selector] and/or visible [text]. Pure and
     * Android-free so the matching rules stay JVM-testable; text goes through [jsonStr], never
     * string interpolation, so there is no JS injection.
     *
     * Matching order is trimmed exact, case-insensitive exact, then a controlled substring pass
     * that only accepts nodes resolving to a real click target — so a wrapping `div`/`span`/`li`
     * is not clicked by accident. When the matched node is not itself clickable the nearest
     * clickable ancestor (or a descendant control) is used, and a `<label>` resolves to its
     * associated control:
     * https://developer.mozilla.org/docs/Web/API/Element/closest
     * https://developer.mozilla.org/docs/Web/API/HTMLLabelElement/control
     * https://developer.mozilla.org/docs/Web/API/HTMLElement/click
     * https://developer.mozilla.org/docs/Web/API/Element/scrollIntoView
     */
    fun clickJs(selector: String?, text: String?): String = buildString {
        append("(function(){")
        append("var trim=function(s){return (s==null?'':String(s)).trim();};")
        append("var clickable='").append(CLICKABLE_SELECTOR).append("';")
        append("var txt=function(e){var s=trim(e.innerText);")
        append("if(!s)s=trim(e.textContent);")
        append("if(!s&&e.getAttribute)s=trim(e.getAttribute('aria-label')||'');")
        append("if(!s&&e.value!=null)s=trim(e.value);return s;};")
        append("var pick=function(e){var c=e.closest(clickable);")
        append("if(c){if(c.tagName==='LABEL'&&c.control)return c.control;return c;}")
        append("var d=e.querySelector(clickable);")
        append("if(d){if(d.tagName==='LABEL'&&d.control)return d.control;return d;}return e;};")
        append("var resolvable=function(e){return !!e.closest(clickable)||!!e.querySelector(clickable);};")
        append("var hit=function(e){var t=pick(e);")
        append("try{if(t.scrollIntoView)t.scrollIntoView({block:'center'});}catch(x){}")
        append("if(t.click)t.click();};")
        if (selector != null) {
            append("var el=document.querySelector(").append(jsonStr(selector)).append(");")
            append("if(el){hit(el);return 'OK';}")
        }
        if (text != null) {
            append("var t=trim(").append(jsonStr(text)).append(");")
            append("var tl=t.toLowerCase();")
            append("var all=document.querySelectorAll('").append(CLICK_BY_TEXT_SELECTOR).append("');")
            append("for(var i=0;i<all.length;i++){if(txt(all[i])===t){hit(all[i]);return 'OK';}}")
            append("for(var j=0;j<all.length;j++){if(txt(all[j]).toLowerCase()===tl){hit(all[j]);return 'OK';}}")
            append("for(var k=0;k<all.length;k++){var e=all[k];if(!resolvable(e))continue;")
            append("if(t&&txt(e).indexOf(t)>=0){hit(e);return 'OK';}}")
        }
        append("return 'NOT_FOUND';})()")
    }

    /**
     * Submits the form of the focused field (or the first form on the page). `requestSubmit()`
     * triggers validation and `submit` events like a real Enter press, unlike `submit()`:
     * https://developer.mozilla.org/docs/Web/API/HTMLFormElement/requestSubmit
     */
    const val SUBMIT_JS =
        "(function(){try{var el=document.activeElement;var f=null;" +
            "if(el&&el.form){f=el.form;}" +
            "if(!f&&document.forms&&document.forms.length>0){f=document.forms[0];}" +
            "if(!f){f=document.querySelector('form');}" +
            "if(!f)return 'NO_FORM';" +
            "if(f.requestSubmit){f.requestSubmit();}else{f.submit();}" +
            "return 'SUBMITTED';}catch(e){return 'ERROR';}})()"
}
