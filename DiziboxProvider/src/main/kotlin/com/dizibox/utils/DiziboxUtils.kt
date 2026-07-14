package com.dizibox.utils

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.jsoup.nodes.Element
import java.net.URLEncoder

object DiziboxUtils {
    val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    val ajaxHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "X-Requested-With" to "XMLHttpRequest"
    )

    fun extractImage(img: Element?, mainUrl: String): String? {
        if (img == null) return null
        val src = img.attr("data-src").ifBlank { img.attr("src") }
        return when {
            src.isBlank() -> null
            src.startsWith("http") -> src
            src.startsWith("//") -> "https:$src"
            else -> mainUrl + src
        }
    }

    fun extractSubtitlesFromEmbed(html: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val trackRegex = Regex("""\{[^}]*?"file"\s*:\s*"([^"]*)"[^}]*?"label"\s*:\s*"([^"]*)"[^}]*?\}""")
        trackRegex.findAll(html).forEach { match ->
            val url = match.groupValues[1].replace("\\/", "/")
            val label = match.groupValues[2]
            if (url.isNotBlank() && label.isNotBlank()) {
                results.add(url to label)
            }
        }
        return results
    }

    fun buildOpenSubtitlesSearchUrl(seriesSlug: String, season: Int, episode: Int): String {
        val query = seriesSlug.replace("-", " ")
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return "https://rest.opensubtitles.org/search/query-$encodedQuery/season-$season/episode-$episode/sublanguageid-eng,tur"
    }

    fun parseOpenSubtitlesResponse(jsonText: String): List<Pair<String, String>> {
        return try {
            val mapper = jacksonObjectMapper()
            val results: List<OpenSubtitleResult> = mapper.readValue(jsonText)
            results.mapNotNull { result ->
                val link = result.downloadLink
                val lang = result.language ?: "Unknown"
                if (!link.isNullOrBlank()) link to lang else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    data class OpenSubtitleResult(
        @JsonProperty("SubFileName") val fileName: String? = null,
        @JsonProperty("SubDownloadLink") val downloadLink: String? = null,
        @JsonProperty("LanguageName") val language: String? = null
    )

    val osHeaders = mapOf(
        "User-Agent" to "TemporaryUserAgent",
        "Accept" to "application/json"
    )
}
