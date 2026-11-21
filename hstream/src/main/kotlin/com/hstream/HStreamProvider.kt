package com.hstream

import android.content.Context
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        "4k-48fps" to "4K 48FPS",
        "uncensored" to "Uncensored",
        "milf" to "MILF",
        "succubus" to "Succubus",
        "big-boobs" to "Big Boobs",
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
    )

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

    private suspend fun getDoc(url: String): Document? = withContext(Dispatchers.IO) {
        runCatching {
            val res = app.get(url, headers = browserHeaders, referer = "$mainUrl/", timeout = 20L)
            if (res.code == 200) res.document else null
        }.getOrNull()
    }

    // Listing cards: <a href=".../hentai/slug-N"><img src="/images/..." alt="Title"></a>
    private fun parseCards(doc: Document, seriesSlug: String? = null): List<SearchResponse> {
        val cards = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for (anchor in doc.select("a[href]")) {
            val href = absolute(anchor.attr("href"))
            if (!href.contains("/hentai/")) continue
            val slug = href.substringAfterLast("/hentai/").substringBefore('/').substringBefore('?')
            if (!Regex(".*-\\d+$").matches(slug)) continue
            if (seriesSlug != null && !slug.startsWith("$seriesSlug-")) continue
            if (!seen.add(href)) continue
            val image = anchor.selectFirst("img[alt]") ?: continue
            val title = image.attr("alt").trim().ifBlank { anchor.attr("title").trim() }
            if (title.isBlank()) continue
            val poster = absolute(image.attr("src"))
            cards.add(
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            )
        }
        return cards
    }

    private fun episodeNumber(slug: String): Int? =
        Regex("-(\\d+)$").find(slug)?.groupValues?.get(1)?.toIntOrNull()

    private fun seriesSlugOf(url: String): String =
        url.substringAfterLast("/hentai/").substringBefore('/').substringBefore('?')

    private fun fullPlot(doc: Document): String? {
        val heading = doc.select("h2").firstOrNull {
            it.text().trim().equals("Description", ignoreCase = true)
        } ?: return null
        return heading.parent()?.selectFirst("p")?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun seriesTags(doc: Document): List<String> =
        doc.select("a[href]")
            .filter { it.attr("href").contains("tags%5B0%5D=") }
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun ogImage(doc: Document): String? =
        doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")?.trim()?.let(::absolute)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        withContext(Dispatchers.IO) {
            val url = "$mainUrl/search?order=recently-uploaded&tags%5B0%5D=${request.data}&page=$page"
            val doc = getDoc(url) ?: return@withContext newHomePageResponse(request, emptyList(), false)
            val cards = parseCards(doc)
            newHomePageResponse(request, cards, cards.size >= 25)
        }

    override suspend fun search(query: String, page: Int): SearchResponseList? =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext newSearchResponseList(emptyList(), false)
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val doc = getDoc("$mainUrl/search?q=$encoded&page=$page")
                ?: return@withContext newSearchResponseList(emptyList(), false)
            val cards = parseCards(doc)
            newSearchResponseList(cards, cards.size >= 25)
        }

    override suspend fun search(query: String): List<SearchResponse> =
        search(query, 1)?.items ?: emptyList()

    override suspend fun load(url: String): LoadResponse? = withContext(Dispatchers.IO) {
        val doc = getDoc(url) ?: return@withContext null
        val slug = seriesSlugOf(url)
        val seriesAnchor = doc.selectFirst("h1 a[href*=\"hentai\"]")
        val isEpisode = seriesAnchor != null
        val seriesUrl = seriesAnchor?.attr("href")?.let(::absolute)
            ?.takeIf { it.contains("/hentai/") }
            ?: "$mainUrl/hentai/${slug.replace(Regex("-\\d+$"), "")}"
        val seriesSlug = seriesSlugOf(seriesUrl)
        val seriesDoc = if (isEpisode) getDoc(seriesUrl) else doc

        val ld = doc.selectFirst("script[type=\"application/ld+json\"]")
            ?.data()
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val episodeTitle = ld?.optString("name")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: slug.replace('-', ' ')
        val title = seriesDoc?.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: episodeTitle
        val plot = fullPlot(seriesDoc ?: doc)
            ?: ld?.optString("description")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim()
        val tags = if (isEpisode) {
            stringList(ld?.optJSONArray("genre"))
        } else {
            seriesTags(doc)
        }
        val year = ld?.optString("uploadDate")?.take(4)?.toIntOrNull()
        val poster = listOfNotNull(
            ogImage(doc),
            ogImage(seriesDoc ?: doc),
        ).firstOrNull()

        val listDoc = seriesDoc ?: doc
        val episodes = parseCards(listDoc, seriesSlug).mapNotNull { card ->
            val episodeSlug = seriesSlugOf(card.url)
            val number = episodeNumber(episodeSlug) ?: return@mapNotNull null
            newEpisode(card.url) {
                this.name = card.name
                this.season = 1
                this.episode = number
                this.posterUrl = card.posterUrl
            }
        }.sortedBy { it.episode }

        if (episodes.isNotEmpty() && (isEpisode || listDoc === doc)) {
            return@withContext newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster.orEmpty()
                this.plot = plot
                this.tags = tags.ifEmpty { null }
                this.year = year
            }
        }

        newMovieLoadResponse(episodeTitle, url, TvType.Movie, url) {
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
        val domains = (stringList(json.optJSONArray("stream_domains")) +
            stringList(json.optJSONArray("asia_stream_domains")))
            .filter { it.startsWith("http") }
            .map { it.trimEnd('/') }
            .distinct()
        if (domains.isEmpty()) return@withContext false
        val base = firstWorkingDomain(domains, streamPath) ?: domains.first()
        val root = "$base/$streamPath"

        val variants = coroutineScope {
            listOf(
                "1080/manifest.mpd",
                "1080i/manifest.mpd",
                "2160/manifest.mpd",
                "2160i/manifest.mpd",
            ).map { path -> async { path to exists("$root/$path") } }.awaitAll()
        }
        val hasSubtitles = exists("$root/eng.ass")

        val links = mutableListOf<StreamLink>()
        links.add(StreamLink("$root/x264.720p.mp4", "HStream 720p (H.264)", 720, ExtractorLinkType.VIDEO))
        for ((path, present) in variants) {
            if (!present) continue
            val height = if (path.startsWith("2160")) 2160 else 1080
            val fps = if (path.contains("i/")) " 48fps" else ""
            links.add(
                StreamLink(
                    "$root/$path",
                    "HStream ${height}p$fps (AV1)",
                    height,
                    ExtractorLinkType.DASH,
                )
            )
        }

        for (link in links.distinctBy { it.url }) {
            callback(
                newExtractorLink("HStream", link.label, link.url, link.type) {
                    this.quality = link.quality
                    this.referer = data
                    this.headers = browserHeaders
                }
            )
        }
        if (hasSubtitles) {
            subtitleCallback(newSubtitleFile("English", "$root/eng.ass"))
        }
        links.isNotEmpty()
    }

    private suspend fun firstWorkingDomain(domains: List<String>, streamPath: String): String? =
        coroutineScope {
            domains.map { base ->
                async {
                    val code = runCatching {
                        app.head(
                            "$base/$streamPath/x264.720p.mp4",
                            headers = browserHeaders,
                            referer = "$mainUrl/",
                            timeout = 6L,
                        ).code
                    }.getOrDefault(0)
                    if (code in 200..299) base else null
                }
            }.awaitAll().firstOrNull { it != null }
        }

    private suspend fun exists(url: String): Boolean = runCatching {
        app.head(url, headers = browserHeaders, referer = "$mainUrl/", timeout = 6L).code in 200..299
    }.getOrDefault(false)

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
