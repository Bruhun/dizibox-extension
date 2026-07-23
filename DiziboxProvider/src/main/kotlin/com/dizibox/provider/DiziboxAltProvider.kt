package com.dizibox.provider

import com.dizibox.utils.DiziboxUtils
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziboxAltProvider : MainAPI() {
    override var mainUrl = "https://www.dizibox.live"
    override var name = "Dizibox Alt"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = DiziboxUtils.headers, referer = "$mainUrl/").document
        val homePageList = ArrayList<HomePageList>()

        val newSeries = document.select("#new-series article.article-series-poster").mapNotNull { parseCard(it) }
        if (newSeries.isNotEmpty()) {
            homePageList.add(HomePageList("Yeni Diziler", newSeries))
        }

        val episodes = document.select("article.article-episode-card").mapNotNull { parseEpisodeCard(it) }
        if (episodes.isNotEmpty()) {
            homePageList.add(HomePageList("Son Bolumler", episodes))
        }

        if (homePageList.isEmpty()) throw ErrorLoadingException("Ana sayfa yuklenemedi")
        return newHomePageResponse(homePageList, false)
    }

    private fun parseCard(element: Element): SearchResponse? {
        val link = element.selectFirst("a.figure-link[href]") ?: return null
        val url = fixUrlNull(link.attr("href")) ?: return null
        val title = element.selectFirst("a.poster-title")?.text()?.trim() ?: return null
        val poster = element.selectFirst("img.afis")?.let { DiziboxUtils.extractImage(it, mainUrl) }

        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
    }

    private fun parseEpisodeCard(element: Element): SearchResponse? {
        val link = element.selectFirst("a.episode-card-title") ?: return null
        val url = fixUrlNull(link.attr("href")) ?: return null
        val title = link.text().trim()
        val poster = element.selectFirst("img.afis")?.let { DiziboxUtils.extractImage(it, mainUrl) }

        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        val document = app.get(searchUrl, headers = DiziboxUtils.headers, referer = "$mainUrl/").document

        val results = document.select("article").mapNotNull { article ->
            val link = article.selectFirst("a[href*=/dizi/], a[href*=/diziler/]") ?: return@mapNotNull null
            val url = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            val title = article.selectFirst("h2, h3")?.text()?.trim()
                ?: link.text().trim().take(100)

            if (title.isBlank()) return@mapNotNull null
            val poster = article.selectFirst("img")?.let { DiziboxUtils.extractImage(it, mainUrl) }

            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = fixUrl(url)
        val doc = app.get(fixedUrl, headers = DiziboxUtils.headers, referer = "$mainUrl/").document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.replace(Regex(" izle \\|.*$"), "")?.trim()
            ?: throw ErrorLoadingException("Dizi basligi bulunamadi")

        val poster = doc.selectFirst("#main-cover img.main-cover")?.let { DiziboxUtils.extractImage(it, mainUrl) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description = doc.selectFirst(".tv-story p")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val year = doc.selectFirst("a[href*=/yil/]")?.text()?.trim()?.toIntOrNull()
        val imdbRating = doc.selectFirst(".label-imdb b")?.text()?.trim()

        val episodeList = ArrayList<Episode>()

        val seasonLinks = doc.select("#seasons-list a.btn")
        if (seasonLinks.isNotEmpty()) {
            for (seasonBtn in seasonLinks) {
                val seasonNum = seasonBtn.text().trim().replace(".", "").takeWhile { it.isDigit() }.toIntOrNull() ?: continue
                val seasonHref = seasonBtn.attr("href").takeIf { it.isNotBlank() } ?: continue
                val seasonUrl = fixUrl(seasonHref)

                try {
                    val seasonDoc = app.get(seasonUrl, headers = DiziboxUtils.headers, referer = fixedUrl).document
                    seasonDoc.select("#category-posts article.grid-box a.season-episode").forEach { link ->
                        val epUrl = fixUrlNull(link.attr("href")) ?: return@forEach
                        val label = link.text().trim()
                        val parts = label.split("Sezon", "Bolum", ".").mapNotNull { it.trim().replace(".", "").toIntOrNull() }
                        val ep = parts.getOrElse(if (parts.size > 1) 1 else parts.getOrElse(0) { 1 }) { 1 }
                        episodeList.add(
                            newEpisode(epUrl) {
                                this.name = label
                                this.season = seasonNum
                                this.episode = ep.coerceAtLeast(1)
                            }
                        )
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (episodeList.isEmpty()) {
            doc.select("#category-posts article.grid-box a.season-episode").forEach { link ->
                val epUrl = fixUrlNull(link.attr("href")) ?: return@forEach
                val label = link.text().trim()
                val parts = label.split("Sezon", "Bolum", ".").mapNotNull { it.trim().replace(".", "").toIntOrNull() }
                val s = parts.getOrElse(0) { 1 }
                val ep = parts.getOrElse(if (parts.size > 1) 1 else 0) { 1 }
                episodeList.add(
                    newEpisode(epUrl) {
                        this.name = label
                        this.season = s.coerceAtLeast(1)
                        this.episode = ep.coerceAtLeast(1)
                    }
                )
            }
        }

        if (episodeList.isEmpty()) throw ErrorLoadingException("Hic bolum bulunamadi")

        return newTvSeriesLoadResponse(title, fixedUrl, TvType.TvSeries, episodeList) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            if (imdbRating != null) this.score = Score.from10(imdbRating.toFloatOrNull() ?: 0.0f)
        }
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

            if (iframeSrc.isNullOrBlank()) return false

            val iframeHtml = app.get(iframeSrc, headers = DiziboxUtils.headers, referer = fixedData).text

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
                        this.referer = iframeSrc
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            return false
        } catch (e: Exception) {
            return false
        }
    }
}
