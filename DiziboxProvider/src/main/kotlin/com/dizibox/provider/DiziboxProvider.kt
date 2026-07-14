package com.dizibox.provider

import com.dizibox.utils.DiziboxUtils
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class DiziboxProvider : MainAPI() {
    override var mainUrl = "https://dizipal2096.com"
    override var name = "Dizibox"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val mapper = jacksonObjectMapper()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = DiziboxUtils.headers, referer = "$mainUrl/").document
        val homePageList = ArrayList<HomePageList>()

        val trendingItems = document.select(".ip-trend-card a").mapNotNull { parseTrendingCard(it) }
        if (trendingItems.isNotEmpty()) {
            homePageList.add(HomePageList("Trendler", trendingItems))
        }

        val latestEpisodes = document.select(".ip-ep-card").mapNotNull { parseEpisodeCard(it) }
        if (latestEpisodes.isNotEmpty()) {
            homePageList.add(HomePageList("Son Bolumler", latestEpisodes))
        }

        val contentCards = document.select(".ip-card a").mapNotNull { parseContentCard(it) }
        if (contentCards.isNotEmpty()) {
            homePageList.add(HomePageList("Populer", contentCards))
        }

        if (homePageList.isEmpty()) throw ErrorLoadingException("Ana sayfa yuklenemedi")
        return newHomePageResponse(homePageList, false)
    }

    private fun parseTrendingCard(element: Element): SearchResponse? {
        val href = element.attr("href").takeIf { it.isNotBlank() } ?: return null
        val url = fixUrlNull(href) ?: return null
        val title = element.selectFirst(".ip-trend-title")?.text()?.trim() ?: return null
        val poster = DiziboxUtils.extractImage(element.selectFirst("img"), mainUrl)
        val type = if (url.contains("/film/")) TvType.Movie else TvType.TvSeries

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    private fun parseEpisodeCard(element: Element): SearchResponse? {
        val href = element.attr("href").takeIf { it.isNotBlank() }
            ?: element.selectFirst("a")?.attr("href")
            ?: return null
        val url = fixUrlNull(href) ?: return null
        val title = element.selectFirst(".ip-ep-title")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val poster = DiziboxUtils.extractImage(element.selectFirst("img"), mainUrl)

        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun parseContentCard(element: Element): SearchResponse? {
        val href = element.attr("href").takeIf { it.isNotBlank() } ?: return null
        val url = fixUrlNull(href) ?: return null
        val title = element.selectFirst(".ip-card-title")?.text()?.trim() ?: return null
        val poster = DiziboxUtils.extractImage(element.selectFirst("img"), mainUrl)
        val badge = element.selectFirst(".ip-badge")?.text()?.trim()?.lowercase() ?: ""
        val type = when {
            badge.contains("film") -> TvType.Movie
            url.contains("/film/") -> TvType.Movie
            else -> TvType.TvSeries
        }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/arama?q=$encodedQuery&ajax=1"
        val response = app.get(searchUrl, headers = DiziboxUtils.ajaxHeaders, referer = "$mainUrl/")

        val results: List<SearchJson> = try {
            mapper.readValue(response.text)
        } catch (e: Exception) {
            throw ErrorLoadingException("Arama sonuclari ayrilamadi: ${e.message}")
        }

        if (results.isEmpty()) return emptyList()

        return results.mapNotNull { item ->
            val url = fixUrl("/${item.type}/${item.slug}")
            val poster = item.poster?.takeIf { it.isNotBlank() }
            when (item.type) {
                "film" -> newMovieSearchResponse(item.title, url, TvType.Movie) {
                    this.posterUrl = poster
                }
                "dizi", "anime" -> newTvSeriesSearchResponse(item.title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                }
                else -> null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = fixUrl(url)

        return when {
            "/dizi/" in fixedUrl -> loadSeriesPage(fixedUrl)
            "/film/" in fixedUrl -> loadMoviePage(fixedUrl)
            else -> {
                val document = app.get(fixedUrl, headers = DiziboxUtils.headers, referer = "$mainUrl/").document
                if (document.selectFirst(".dp-season-btns, .ip-ep-card") != null) {
                    loadSeriesPage(fixedUrl, document)
                } else {
                    loadMoviePage(fixedUrl, document)
                }
            }
        }
    }

    private suspend fun loadSeriesPage(url: String, document: Document? = null): LoadResponse? {
        val doc = document ?: app.get(url, headers = DiziboxUtils.headers, referer = "$mainUrl/").document
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Dizi basligi bulunamadi")

        val poster = doc.selectFirst(".ip-hero")?.attr("style")
            ?.let { Regex("url\\(['\"]?(.*?)['\"]?\\)").find(it)?.groupValues?.get(1) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description = doc.selectFirst("p.dp-desc")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = doc.selectFirst(".dp-info-val")?.text()?.filter { it.isDigit() }?.toIntOrNull()
            ?: doc.selectFirst("meta[property=og:video:release_date]")?.attr("content")?.take(4)?.toIntOrNull()

        val slug = url.substringAfter("$mainUrl/dizi/").substringAfter("/dizi/").trim('/')
        val episodeList = ArrayList<Episode>()

        val seasonNumbers = doc.select(".dp-season-btn").mapNotNull { btn ->
            btn.attr("data-season").toIntOrNull()
        }.toMutableSet()
        if (seasonNumbers.isEmpty()) {
            seasonNumbers.add(1)
        }

        try {
            val s1e1Url = fixUrl("/bolum/$slug-1-sezon-1-bolum")
            val s1e1Doc = app.get(s1e1Url, headers = DiziboxUtils.headers, referer = url).document
            s1e1Doc.select(".bp-stab").mapNotNull { tab ->
                tab.attr("data-season").toIntOrNull()
            }.forEach { seasonNumbers.add(it) }
        } catch (_: Exception) {
        }

        val sortedSeasons = seasonNumbers.sorted()

        coroutineScope {
            sortedSeasons.map { season ->
                async {
                    discoverSeasonEpisodes(season, slug, url)
                }
            }.awaitAll().forEach { eps -> episodeList.addAll(eps) }
        }

        if (episodeList.isEmpty()) {
            throw ErrorLoadingException("Hic bolum bulunamadi")
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
        }
    }

    private suspend fun discoverSeasonEpisodes(season: Int, slug: String, referer: String): List<Episode> {
        val episodes = ArrayList<Episode>()
        var episodeNum = 1
        var consecutiveFails = 0
        while (consecutiveFails < 5 && episodeNum <= 30) {
            if (episodeNum > 1) delay(50L)
            val epUrl = fixUrl("/bolum/$slug-$season-sezon-$episodeNum-bolum")
            val epTitle = fetchEpisodeTitle(epUrl, referer)
            if (!epTitle.isNullOrBlank()) {
                consecutiveFails = 0
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epTitle
                        this.season = season
                        this.episode = episodeNum
                    }
                )
            } else {
                consecutiveFails++
            }
            episodeNum++
        }
        return episodes
    }

    private suspend fun fetchEpisodeTitle(epUrl: String, referer: String): String? {
        suspend fun attempt(): String? {
            val epDoc = app.get(epUrl, headers = DiziboxUtils.headers, referer = referer).document
            return epDoc.selectFirst("h1.bp-ep-title, h1")?.text()?.trim()
        }
        return try {
            attempt()
        } catch (_: Exception) {
            delay(500L)
            try { attempt() } catch (_: Exception) { null }
        }
    }

    private suspend fun loadMoviePage(url: String, document: Document? = null): LoadResponse? {
        val doc = document ?: app.get(url, headers = DiziboxUtils.headers, referer = "$mainUrl/").document
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Film basligi bulunamadi")

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".ip-poster img")?.let { DiziboxUtils.extractImage(it, mainUrl) }

        val description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val plotName = title

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedData = fixUrl(data)

        val embedUrl = when {
            "/film/" in fixedData -> {
                val slug = fixedData.substringAfter("$mainUrl/film/").substringAfter("/film/").trim('/')
                "$mainUrl/api/embed.php?slug=${URLEncoder.encode(slug, "UTF-8")}&domain=$mainUrl&type=film"
            }
            "/bolum/" in fixedData -> {
                val slug = fixedData.substringAfter("$mainUrl/bolum/").substringAfter("/bolum/").trim('/')
                "$mainUrl/api/embed.php?slug=${URLEncoder.encode(slug, "UTF-8")}&domain=$mainUrl&type=dizi"
            }
            else -> {
                val doc = app.get(fixedData, headers = DiziboxUtils.headers, referer = "$mainUrl/").document
                val iframeSrc = doc.selectFirst("iframe")?.attr("src")
                if (iframeSrc != null) return resolveEmbed(fixUrl(iframeSrc), subtitleCallback, callback)
                return false
            }
        }

        return resolveEmbed(embedUrl, subtitleCallback, callback)
    }

    private suspend fun resolveEmbed(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(embedUrl, headers = DiziboxUtils.headers, referer = "$mainUrl/").text
            val hlsUrl = Regex("""var\s+(hlsSrc|src)\s*=\s*["'`]([^"'`]+)["'`]""").find(html)?.groupValues?.let { it.getOrNull(2) }
            var subsFound = false

            DiziboxUtils.extractSubtitlesFromEmbed(html) { url, label ->
                subsFound = true
                subtitleCallback.invoke(newSubtitleFile(label, url))
            }

            if (!subsFound) {
                fetchOpenSubtitlesIfEpisode(embedUrl, subtitleCallback)
            }

            if (!hlsUrl.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun fetchOpenSubtitlesIfEpisode(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val slugMatch = Regex("""slug=([^&]+)""").find(embedUrl) ?: return
        val slug = URLDecoder.decode(slugMatch.groupValues[1], "UTF-8")

        val seasonMatch = Regex("-(\\d+)-sezon-").find(slug)
        val episodeMatch = Regex("-(\\d+)-bolum").find(slug)
        val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: return
        val episode = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: return

        val seriesSlug = slug.replace(Regex("-\\d+-sezon-\\d+-bolum\$"), "")
            .replace(Regex("-\\d+-sezon-.*\$"), "")
            .takeIf { it.isNotBlank() } ?: return

        val results = DiziboxUtils.searchOpenSubtitles(app, seriesSlug, season, episode)
        results.take(4).forEach { (url, lang) ->
            subtitleCallback.invoke(newSubtitleFile(lang, url))
        }
    }

    data class SearchJson(
        @JsonProperty("slug") val slug: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("year") val year: Int? = null
    )
}
