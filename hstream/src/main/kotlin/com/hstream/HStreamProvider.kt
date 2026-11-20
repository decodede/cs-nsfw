package com.hstream

import android.content.Context
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder

@CloudstreamPlugin
class HStreamPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HStreamProvider())
    }
}

class HStreamProvider : MainAPI() {
    override var name = "HStream"
    override var mainUrl = "https://hstream.moe"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    override val mainPage = mainPageOf(
        "4K 48FPS" to "4k-48fps",
        "Uncensored" to "uncensored",
        "MILF" to "milf",
        "Succubus" to "succubus",
        "Big Boobs" to "big-boobs",
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private suspend fun getDoc(url: String): Document? = withContext(Dispatchers.IO) {
        runCatching {
            val res = app.get(url, headers = browserHeaders, referer = "$mainUrl/", timeout = 20L)
            if (res.code == 200) res.document else null
        }.getOrNull()
    }

    private fun absolute(url: String): String {
        val clean = url.trim()
        return when {
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> mainUrl + clean
            clean.isBlank() -> ""
            else -> "$mainUrl/$clean"
        }
    }

    // Listing cards: <a href=".../hentai/slug-N"><img src="/images/..." alt="Title"></a>
    private fun parseCards(doc: Document): List<SearchResponse> {
        val cards = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for (anchor in doc.select("a[href]")) {
            val href = absolute(anchor.attr("href"))
            if (!href.contains("/hentai/")) continue
            val slug = href.substringAfterLast("/hentai/").substringBefore('/').substringBefore('?')
            if (!Regex(".*-\\d+$").matches(slug)) continue
            if (!seen.add(href)) continue
            val image = anchor.selectFirst("img[alt]") ?: continue
            val title = image.attr("alt").trim().ifBlank { anchor.attr("title").trim() }
            if (title.isBlank()) continue
            val poster = absolute(image.attr("src"))
            cards.add(
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            )
        }
        return cards
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        withContext(Dispatchers.IO) {
            val url = "$mainUrl/search?order=recently-uploaded&tags%5B0%5D=${request.data}&page=$page"
            val doc = getDoc(url) ?: return@withContext newHomePageResponse(request, emptyList(), false)
            val cards = parseCards(doc)
            newHomePageResponse(request, cards, cards.size >= 25)
        }

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = getDoc("$mainUrl/search?q=$encoded") ?: return@withContext emptyList()
        parseCards(doc)
    }

    override suspend fun load(url: String): LoadResponse? = withContext(Dispatchers.IO) {
        val doc = getDoc(url) ?: return@withContext null
        val ld = doc.selectFirst("script[type=\"application/ld+json\"]")
            ?.data()
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val slug = url.substringAfterLast("/hentai/").substringBefore('/').substringBefore('?')
        val title = ld?.optString("name")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: slug.replace('-', ' ')
        val cover = doc.selectFirst("img[src*=\"cover-ep-\"]")?.attr("src")?.let(::absolute)
        val poster = listOfNotNull(
            cover?.takeIf { it.isNotBlank() },
            ld?.optString("thumbnailUrl")?.trim()?.takeIf { it.isNotBlank() }?.let(::absolute),
            doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")?.trim()?.let(::absolute),
        ).firstOrNull()
        val plot = ld?.optString("description")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim()
        val tags = stringList(ld?.optJSONArray("genre"))
        val year = ld?.optString("uploadDate")?.take(4)?.toIntOrNull()

        newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster.orEmpty()
            this.plot = plot
            this.tags = tags.ifEmpty { null }
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val res = runCatching {
            app.get(data, headers = browserHeaders, referer = "$mainUrl/", timeout = 20L)
        }.getOrNull() ?: return@withContext false
        if (res.code != 200) return@withContext false
        val doc = runCatching { res.document }.getOrNull() ?: return@withContext false
        val episodeId = doc.selectFirst("input#e_id")?.attr("value")?.trim()
            ?.takeIf { it.isNotBlank() } ?: return@withContext false

        val xsrf = cookie(res.cookies, "XSRF-TOKEN")?.let {
            runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
        }
        val apiHeaders = HashMap(browserHeaders).apply {
            put("Accept", "application/json")
            put("X-Requested-With", "XMLHttpRequest")
            if (!xsrf.isNullOrBlank()) put("X-XSRF-TOKEN", xsrf)
        }
        val payload = JSONObject().put("episode_id", episodeId)
        val response = runCatching {
            app.post(
                "$mainUrl/player/api",
                headers = apiHeaders,
                cookies = res.cookies,
                referer = data,
                json = payload,
                timeout = 20L,
            ).text
        }.getOrNull() ?: return@withContext false
        val json = runCatching { JSONObject(response) }.getOrNull() ?: return@withContext false

        val streamPath = json.optString("stream_url").trim().trimStart('/')
        if (streamPath.isBlank()) return@withContext false
        val domains = stringList(json.optJSONArray("stream_domains")) +
            stringList(json.optJSONArray("asia_stream_domains"))
        val base = domains.firstOrNull { it.startsWith("http") }?.trimEnd('/')
            ?: return@withContext false
        val root = "$base/$streamPath"

        val links = mutableListOf<StreamLink>()
        links.add(StreamLink("$root/x264.720p.mp4", "HStream 720p", 720, ExtractorLinkType.VIDEO))
        if (json.optInt("interpolated", 0) == 1) {
            links.add(StreamLink("$root/1080/manifest.mpd", "HStream 1080p", 1080, ExtractorLinkType.DASH))
            links.add(StreamLink("$root/2160/manifest.mpd", "HStream 2160p", 2160, ExtractorLinkType.DASH))
            links.add(StreamLink("$root/1080i/manifest.mpd", "HStream 1080p 48fps", 1080, ExtractorLinkType.DASH))
        }
        if (json.optInt("interpolated_uhd", 0) == 1) {
            links.add(StreamLink("$root/2160i/manifest.mpd", "HStream 2160p 48fps", 2160, ExtractorLinkType.DASH))
        }

        var emitted = false
        for (link in links.distinctBy { it.url }) {
            callback(
                newExtractorLink("HStream", link.label, link.url, link.type) {
                    this.quality = link.quality
                    this.referer = data
                    this.headers = browserHeaders
                }
            )
            emitted = true
        }
        subtitleCallback(SubtitleFile("English", "$root/eng.vtt"))
        emitted
    }

    private data class StreamLink(
        val url: String,
        val label: String,
        val quality: Int,
        val type: ExtractorLinkType,
    )

    private fun cookie(cookies: Map<String, String>, name: String): String? =
        cookies.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).trim().takeIf { it.isNotBlank() }
        }
    }
}
