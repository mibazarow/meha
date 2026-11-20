package com.andharness.ru.data.tools.web

import com.andharness.ru.domain.formatting.collapseWhitespace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * Keyless image-search client for the `image_search` tool. It queries CC-licensed image catalogs
 * through their documented JSON APIs (no API key, no HTML scraping):
 *
 * - Openverse v1 images (default): `https://api.openverse.org/v1/images/`
 *   — the `url` field of each `result` is a DIRECT image file URL.
 *   Docs: https://api.openverse.org/v1/images/
 * - Wikimedia Commons (alternative): `https://commons.wikimedia.org/w/api.php`
 *   — `list/search` (namespace 6 = File) to find files, then `prop=imageinfo` + `iiprop=dimensions|url|extmetadata`
 *   to resolve the DIRECT url, dimensions and license.
 *   Docs: https://commons.wikimedia.org/w/api.php
 *
 * Both sources return DIRECT image URLs so the agent can hand one straight to `download_file` /
 * `wallpaper_set`. When the requested source returns nothing (empty, HTTP error or exception) the
 * client transparently falls back to the other source; if both fail the result list is empty and the
 * caller reports an honest error. All network I/O runs on [Dispatchers.IO] through the injected
 * [OkHttpClient] (which already carries bounded connect/read/write timeouts).
 */
class ImageSearchClient(
    private val okHttpClient: OkHttpClient?,
    private val openverseBaseUrl: String = "https://api.openverse.org/v1/images/",
    private val commonsBaseUrl: String = "https://commons.wikimedia.org/w/api.php",
) {

    /** One image result with a DIRECT image [url] and its metadata. */
    data class Result(
        val title: String,
        val url: String,
        val author: String,
        val source: String,
        val license: String,
        val width: Int?,
        val height: Int?,
    )

    /** Supported catalogs. [OPENVERSE] is the default; [WIKIMEDIA] is the fallback alternative. */
    enum class Src { OPENVERSE, WIKIMEDIA }

    /**
     * Returns the catalog that actually supplied the results together with those results. When the
     * requested [source] fails and the other one succeeds, the *other* [Src] is returned so the
     * caller can honestly report which source was used. An empty list means both sources failed.
     */
    suspend fun search(query: String, count: Int, orientation: String?, source: Src): Pair<Src, List<Result>> =
        withContext(Dispatchers.IO) {
            val client = okHttpClient ?: return@withContext Pair(source, emptyList())
            try {
                val first = fetch(client, source, query, count, orientation)
                if (first.isNotEmpty()) return@withContext Pair(source, first)
            } catch (e: Exception) {
                // fall through to the other catalog
            }
            val other = if (source == Src.OPENVERSE) Src.WIKIMEDIA else Src.OPENVERSE
            try {
                val fetched = fetch(client, other, query, count, orientation)
                if (fetched.isNotEmpty()) return@withContext Pair(other, fetched)
            } catch (e: Exception) {
                // both failed
            }
            Pair(source, emptyList())
        }

    private fun fetch(
        client: OkHttpClient,
        src: Src,
        query: String,
        count: Int,
        orientation: String?,
    ): List<Result> = when (src) {
        Src.OPENVERSE -> openverse(client, query, count, orientation)
        Src.WIKIMEDIA -> wikimedia(query, count)
    }

    private fun openverse(
        client: OkHttpClient,
        query: String,
        count: Int,
        orientation: String?,
    ): List<Result> {
        val params = buildString {
            append("q=")
            append(URLEncoder.encode(query, "UTF-8"))
            append("&page_size=")
            append(count)
            if (orientation.isNullOrBlank()) return@buildString
            append("&orientation=")
            append(URLEncoder.encode(orientation, "UTF-8"))
        }
        val url = baseUrlWithQuery(openverseBaseUrl, params)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            parseOpenverse(resp.body?.string())
        }
    }

    private fun parseOpenverse(body: String?): List<Result> {
        val obj = runCatching { Json.parseToJsonElement(body ?: "").jsonObject }.getOrNull() ?: return emptyList()
        val arr = obj["results"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val imageUrl = (o["url"] as? JsonPrimitive)?.content.orEmpty().trim()
            if (imageUrl.isBlank()) return@mapNotNull null
            Result(
                title = (o["title"] as? JsonPrimitive)?.content.orEmpty().trim(),
                url = imageUrl,
                author = (o["creator"] as? JsonPrimitive)?.content.orEmpty().trim(),
                source = "openverse",
                license = licenseOf(
                    (o["license"] as? JsonPrimitive)?.content.orEmpty(),
                    (o["license_version"] as? JsonPrimitive)?.content.orEmpty(),
                ),
                width = (o["width"] as? JsonPrimitive)?.content?.toIntOrNull(),
                height = (o["height"] as? JsonPrimitive)?.content?.toIntOrNull(),
            )
        }
    }

    private fun wikimedia(query: String, count: Int): List<Result> {
        val searchUrl = baseUrlWithQuery(
            commonsBaseUrl,
            "action=query&format=json&formatversion=2&list=search&srnamespace=6&srlimit=$count&srsearch=" +
                URLEncoder.encode(query, "UTF-8"),
        )
        val searchObj = getCached(searchUrl)
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: return emptyList()
        val titles = searchObj["query"]?.jsonObject
            ?.get("search")?.let { (it as? JsonArray)?.mapNotNull { e -> (e as? JsonObject)?.get("title")?.jsonPrimitive?.content } }
            ?: return emptyList()
        if (titles.isEmpty()) return emptyList()
        val infoUrl = baseUrlWithQuery(
            commonsBaseUrl,
            "action=query&format=json&formatversion=2&prop=imageinfo&iiprop=dimensions|url|extmetadata&titles=" +
                titles.joinToString("|") { t -> URLEncoder.encode(t, "UTF-8") },
        )
        val infoObj = getCached(infoUrl)
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: return emptyList()
        val pages = infoObj["query"]?.jsonObject?.get("pages")?.jsonArray ?: return emptyList()
        return pages.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val ii = o["imageinfo"]?.jsonArray?.firstOrNull() as? JsonObject ?: return@mapNotNull null
            val imageUrl = (ii["url"] as? JsonPrimitive)?.content.orEmpty().trim()
            if (imageUrl.isBlank()) return@mapNotNull null
            val meta = ii["extmetadata"] as? JsonObject
            Result(
                title = (o["title"] as? JsonPrimitive)?.content.orEmpty().trim().removePrefix("File:"),
                url = imageUrl,
                author = stripHtml(meta?.get("Artist")?.jsonObject?.get("value")?.jsonPrimitive?.content.orEmpty()),
                source = "wikimedia",
                license = stripHtml(
                    meta?.get("LicenseShortName")?.jsonObject?.get("value")?.jsonPrimitive?.content.orEmpty(),
                ),
                width = (ii["width"] as? JsonPrimitive)?.content?.toIntOrNull(),
                height = (ii["height"] as? JsonPrimitive)?.content?.toIntOrNull(),
            )
        }
    }

    /** Appends [query] to [base] using `?` or `&` depending on whether [base] already has one. */
    private fun baseUrlWithQuery(base: String, query: String): String {
        val sep = if (base.contains("?")) "&" else "?"
        return "$base$sep$query"
    }

    /** One GET through the injected client, returning the body or null on any failure. */
    private fun getCached(url: String): String? {
        val client = okHttpClient ?: return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        return runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                resp.body?.string()
            }
        }.getOrNull()
    }

    private fun licenseOf(license: String, version: String): String {
        val base = license.trim()
        if (base.isBlank()) return version.trim()
        if (version.isBlank()) return base
        return "$base.$version"
    }

    private fun stripHtml(value: String): String =
        collapseWhitespace(value.replace(HTML_TAG, " "))

    private companion object {
        const val USER_AGENT = "Andharness/1.0 (keyless image search)"
        private val HTML_TAG = Regex("<[^>]+>")
    }
}
