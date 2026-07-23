package com.dizibox.provider

import com.dizibox.utils.DiziboxUtils
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziboxProvider : MainAPI() {
    override var mainUrl = "https://www.dizibox.live"
    override var name = "Dizibox"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = DiziboxUtils.headers, referer = "$mainUrl/").document
        val homePageList = ArrayList<HomePageList>()

        val recommended = document.select("#recommended_series li").mapNotNull { parseRecommendedItem(it) }
        if (recommended.isNotEmpty()) {
            homePageList.add(HomePageList("Beklenen / Eklenen Diziler", recommended))
        }

        val newSeries = document.select("#new-series article.article-series-poster").mapNotNull { parsePosterCard(it) }
        if (newSeries.isNotEmpty()) {
            homePageList.add(HomePageList("Dikkat Ceken Yeni Diziler", newSeries))
        }

        val latestEpisodes = document.select("article.article-episode-card").mapNotNull { parseEpisodeCard(it) }
        if (latestEpisodes.isNotEmpty()) {
            homePageList.add(HomePageList("Son Bolumler", latestEpisodes))
        }

        val bestSeries = document.select("#best-series article.article-series-small-grid").mapNotNull { parseSmallGridCard(it) }
        val recommendedSeries = document.select("#recommended-series article.article-series-small-grid").mapNotNull { parseSmallGridCard(it) }
        if (bestSeries.isNotEmpty()) {
            homePageList.add(HomePageList("Efsane Diziler", bestSeries))
        }
        if (recommendedSeries.isNotEmpty()) {
            homePageList.add(HomePageList("Onerilen Diziler", recommendedSeries))
        }

        if (homePageList.isEmpty()) throw ErrorLoadingException("Ana sayfa yuklenemedi")
        return newHomePageResponse(homePageList, false)
    }

    private fun parseRecommendedItem(element: Element): SearchResponse? {
        val slug = element.attr("data-link").takeIf { it.isNotBlank() } ?: return null
        val title = element.attr("data-title").takeIf { it.isNotBlank() } ?: return null
        val url = fixUrl("/diziler/$slug")
        val poster = element.selectFirst("img")?.let { DiziboxUtils.extractImage(it, mainUrl) }

        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun parsePosterCard(element: Element): SearchResponse? {
        val link = element.selectFirst("a.figure-link[href]") ?: return null
        val url = fixUrlNull(link.attr("href")) ?: return null
        val title = element.selectFirst("a.poster-title")?.text()?.trim() ?: return null
        val poster = element.selectFirst("img.afis")?.let { DiziboxUtils.extractImage(it, mainUrl) }

        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun parseEpisodeCard(element: Element): SearchResponse? {
        val link = element.selectFirst("a.episode-card-title") ?: return null
        val url = fixUrlNull(link.attr("href")) ?: return null
        val seriesName = link.selectFirst("b.series-name")?.text()?.trim() ?: return null
        val seasonText = link.selectFirst("span.season")?.text()?.trim() ?: ""
        val episodeText = link.selectFirst("b.episode")?.text()?.trim() ?: ""
        val title = "$seriesName - $seasonText $episodeText".trim()
        val poster = element.selectFirst("img.afis")?.let { DiziboxUtils.extractImage(it, mainUrl) }

        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun parseSmallGridCard(element: Element): SearchResponse? {
        val link = element.selectFirst("a.series-details") ?: element.selectFirst("a[href]")
        val url = fixUrlNull(link?.attr("href") ?: "") ?: return null
        val title = element.selectFirst("div.tv-title")?.text()?.trim()
            ?: element.selectFirst("a.series-details")?.text()?.trim()
            ?: return null
        val poster = element.selectFirst("img.afis")?.let { DiziboxUtils.extractImage(it, mainUrl) }

        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        return try {
            searchViaWordPress(encodedQuery)
        } catch (_: Exception) {
            searchViaArchive(query)
        }
    }

    private suspend fun searchViaWordPress(encodedQuery: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/?s=$encodedQuery",
            headers = DiziboxUtils.headers,
            referer = "$mainUrl/"
        ).document
        return document.select("article").mapNotNull { parseSearchResult(it) }
    }

    private suspend fun searchViaArchive(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/arsiv/",
            headers = DiziboxUtils.headers,
            referer = "$mainUrl/"
        ).document

        val queryLower = query.lowercase().trim()
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        document.select(".alphabetical-category-list a[href]").forEach { link ->
            val href = link.attr("href")
            val title = link.text().trim()
            if (title.lowercase().contains(queryLower) && href.contains("/dizi")) {
                val url = fixUrlNull(href) ?: return@forEach
                if (seen.add(url)) {
                    results.add(
                        newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                            this.posterUrl = null
                        }
                    )
                }
            }
        }

        if (results.isEmpty()) {
            document.select("article a[href*=/dizi/]").forEach { link ->
                val title = link.text().trim()
                if (title.lowercase().contains(queryLower)) {
                    val url = fixUrlNull(link.attr("href")) ?: return@forEach
                    if (seen.add(url)) {
                        results.add(
                            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                                this.posterUrl = null
                            }
                        )
                    }
                }
            }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = fixUrl(url)
        var document = try {
            app.get(fixedUrl, headers = DiziboxUtils.headers, referer = "$mainUrl/").document
        } catch (e: Exception) {
            val altUrl = fixedUrl.replace("/diziler/", "/dizi/")
            if (altUrl != fixedUrl) {
                app.get(altUrl, headers = DiziboxUtils.headers, referer = "$mainUrl/").document
            } else throw e
        }

        if (document.selectFirst("#category-posts") != null || document.selectFirst("#seasons-list") != null || document.selectFirst(".tv-overview") != null) {
            return loadSeriesPage(fixedUrl, document)
        }

        if (document.selectFirst("#video-area") != null) {
            val seriesUrl = resolveSeriesUrlFromEpisode(document)
            if (seriesUrl != null) {
                return loadSeriesPage(seriesUrl)
            }
        }

        val altUrl = fixedUrl.replace("/diziler/", "/dizi/")
        if (altUrl != fixedUrl) {
            document = app.get(altUrl, headers = DiziboxUtils.headers, referer = "$mainUrl/").document
            return loadSeriesPage(altUrl, document)
        }

        throw ErrorLoadingException("Icerik bulunamadi")
    }

    private fun resolveSeriesUrlFromEpisode(document: Document): String? {
        return document.selectFirst("a[href*=/dizi/], a[href*=/diziler/]")?.attr("href")
    }

    data class SeasonInfo(val number: Int, val url: String, val episodes: List<Episode>)

    private suspend fun loadSeriesPage(seriesUrl: String, initialDoc: Document? = null): LoadResponse {
        val doc = initialDoc ?: app.get(seriesUrl, headers = DiziboxUtils.headers, referer = "$mainUrl/").document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.replace(Regex(" izle \\|.*$"), "")?.trim()
            ?: throw ErrorLoadingException("Dizi basligi bulunamadi")

        val poster = doc.selectFirst("#main-cover img.main-cover")?.let { DiziboxUtils.extractImage(it, mainUrl) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description = doc.selectFirst(".tv-story p")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val year = doc.selectFirst("a[href*=/yil/]")?.text()?.trim()?.toIntOrNull()
        val imdbRating = doc.selectFirst(".label-imdb b")?.text()?.trim()

        val seasonUrls = mutableListOf<Pair<Int, String>>()
        doc.select("#seasons-list a.btn").forEach { btn ->
            val seasonNum = btn.text().trim().replace(".", "").takeWhile { it.isDigit() }.toIntOrNull()
            val href = btn.attr("href").takeIf { it.isNotBlank() }
            if (seasonNum != null && href != null) {
                seasonUrls.add(seasonNum to fixUrl(href))
            }
        }

        val episodeList = ArrayList<Episode>()

        if (seasonUrls.isNotEmpty()) {
            coroutineScope {
                seasonUrls.map { (seasonNum, seasonUrl) ->
                    async {
                        try {
                            val seasonDoc = app.get(seasonUrl, headers = DiziboxUtils.headers, referer = seriesUrl).document
                            val episodes = parseSeasonEpisodes(seasonDoc, seasonNum)
                            SeasonInfo(seasonNum, seasonUrl, episodes)
                        } catch (e: Exception) {
                            SeasonInfo(seasonNum, seasonUrl, emptyList())
                        }
                    }
                }.awaitAll().forEach { seasonInfo ->
                    episodeList.addAll(seasonInfo.episodes)
                }
            }
        }

        if (episodeList.isEmpty()) {
            val currentSeasonEpisodes = doc.select("#category-posts article.grid-box a.season-episode").mapNotNull { link ->
                val epUrl = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                val label = link.text().trim()
                val (s, ep) = parseSeasonEpisodeFromLabel(label)
                newEpisode(epUrl) {
                    this.name = label
                    this.season = s
                    this.episode = ep
                }
            }
            episodeList.addAll(currentSeasonEpisodes)
        }

        if (episodeList.isEmpty()) {
            throw ErrorLoadingException("Hic bolum bulunamadi")
        }

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodeList) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            if (imdbRating != null) {
                this.score = Score.from10(imdbRating.toFloatOrNull() ?: 0.0f)
            }
        }
    }

    private fun parseSeasonEpisodes(doc: Document, season: Int): List<Episode> {
        return doc.select("#category-posts article.grid-box a.season-episode").mapNotNull { link ->
            val epUrl = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val label = link.text().trim()
            val (_, episode) = parseSeasonEpisodeFromLabel(label)
            newEpisode(epUrl) {
                this.name = label
                this.season = season
                this.episode = episode
            }
        }
    }

    private fun parseSeasonEpisodeFromLabel(label: String): Pair<Int, Int> {
        val parts = label.split("Sezon", "Bolum", ".")
            .mapNotNull { it.trim().replace(".", "").toIntOrNull() }
        val season = parts.getOrElse(0) { 1 }
        val episode = parts.getOrElse(if (parts.size > 1) 1 else 0) { 1 }
        return Pair(season.coerceAtLeast(1), episode.coerceAtLeast(1))
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedData = fixUrl(data)

        try {
            val doc = app.get(fixedData, headers = DiziboxUtils.headers, referer = "$mainUrl/").document
            val iframeSrc = doc.selectFirst("#video-area iframe[src]")?.attr("src")

            if (iframeSrc.isNullOrBlank()) {
                val fallbackIframe = doc.selectFirst("iframe[src*=/player/]")?.attr("src")
                if (fallbackIframe != null) {
                    return resolvePlayerIframe(fallbackIframe, fixedData, subtitleCallback, callback)
                }
                return false
            }

            return resolvePlayerIframe(iframeSrc, fixedData, subtitleCallback, callback)
        } catch (e: Exception) {
            return false
        }
    }

    private suspend fun resolvePlayerIframe(
        iframeUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val iframeHtml = app.get(iframeUrl, headers = DiziboxUtils.headers, referer = referer).text

            val embedSubs = DiziboxUtils.extractSubtitlesFromEmbed(iframeHtml)
            embedSubs.forEach { (url, label) ->
                subtitleCallback.invoke(newSubtitleFile(label, url))
            }

            val hlsUrl = DiziboxUtils.extractHlsUrl(iframeHtml)
                ?: DiziboxUtils.extractVideoUrl(iframeHtml)

            if (!hlsUrl.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = iframeUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            val nestedIframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
            if (nestedIframe != null) {
                return resolvePlayerIframe(nestedIframe, iframeUrl, subtitleCallback, callback)
            }

            return false
        } catch (e: Exception) {
            return false
        }
    }
}
