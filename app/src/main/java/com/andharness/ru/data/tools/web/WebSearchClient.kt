package com.andharness.ru.data.tools.web

import com.andharness.ru.data.osint.OsintParsing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The `web_search` extraction path: parses the search-results page both from the in-page JS result
 * and, as a fallback, from the plain HTML fetched with OkHttp.
 */
internal class WebSearchClient(private val okHttpClient: OkHttpClient?) {

    data class Result(val title: String, val url: String, val snippet: String)

    fun resultsFromJs(raw: String?): List<Result> {
        val arr = WebContentExtractor.parseJsonArray(raw) ?: return emptyList()
        return arr.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = (obj["title"] as? JsonPrimitive)?.content.orEmpty().trim()
            val url = (obj["url"] as? JsonPrimitive)?.content.orEmpty().trim()
            val snippet = (obj["snippet"] as? JsonPrimitive)?.content.orEmpty().trim()
            if (url.isBlank()) null else Result(title, url, snippet)
        }
    }

    suspend fun htmlFallback(searchUrl: String): List<Result> = withContext(Dispatchers.IO) {
        val client = okHttpClient ?: return@withContext emptyList()
        try {
            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", WebContentExtractor.MOBILE_CHROME_UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                OsintParsing.parseSearchHtml(body).map { Result(it.title, it.url, it.snippet) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        const val SEARCH_URL = "https://duckduckgo.com/html/?q="

        const val SEARCH_JS =
            "(function(){" +
                "function unwrap(h){try{var u=new URL(h,location.href);" +
                "if(/(^|\\.)duckduckgo\\.com$/.test(u.hostname)&&u.pathname.indexOf('/l/')===0){" +
                "var t=u.searchParams.get('uddg');if(t)return decodeURIComponent(t);}return u.href;}catch(e){return h;}}" +
                "function clean(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "var out=[],seen={};" +
                "function add(a,snip){var href=a.getAttribute('href')||'';if(!href||href.charAt(0)==='#')return;" +
                "var url=unwrap(href);if(!/^https?:/i.test(url))return;var title=clean(a.textContent);" +
                "if(title.length<2)return;if(seen[url])return;seen[url]=1;" +
                "out.push({title:title,url:url,snippet:clean(snip)});}" +
                "var nodes=document.querySelectorAll('a.result__a');" +
                "for(var i=0;i<nodes.length;i++){var n=nodes[i];var r=n.closest?n.closest('.result, .web-result, li'):null;" +
                "var sn='';if(r){var se=r.querySelector('.result__snippet, .result-snippet, .snippet');if(se)sn=se.textContent;}" +
                "add(n,sn);}" +
                "if(out.length===0){var all=document.querySelectorAll('#links a, #search a, .results a, main a, article a');" +
                "for(var j=0;j<all.length;j++){add(all[j],'');if(out.length>=40)break;}}" +
                "return JSON.stringify(out);})()"
    }
}
