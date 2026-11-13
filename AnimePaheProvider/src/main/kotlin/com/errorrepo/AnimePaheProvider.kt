package com.errorrepo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class AnimePaheProvider : MainAPI() {
    override var mainUrl = "https://animepahe.ch"
    override var name = "AnimePahe"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "series/?status=&type=&order=update" to "Latest Updates",
        "series/?status=&type=&order=popular" to "Popular",
        "series/?status=ongoing&type=&order=update" to "Ongoing",
        "series/?status=completed&type=&order=update" to "Completed",
        "series/?status=&type=Movie&order=update" to "Movies",
        "genres/action" to "Action",
        "genres/adventure" to "Adventure",
        "genres/comedy" to "Comedy",
        "genres/drama" to "Drama",
        "genres/fantasy" to "Fantasy",
        "genres/isekai" to "Isekai",
        "genres/romance" to "Romance",
        "genres/seinen" to "Seinen",
        "genres/shounen" to "Shounen",
        "genres/supernatural" to "Supernatural"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val currentPage = page.coerceAtLeast(1)
        val path = request.data.trimStart('/')
        val url = if (path.startsWith("series/")) {
            if (currentPage <= 1) {
                "$mainUrl/$path"
            } else {
                val sep = if (path.contains("?")) "&" else "?"
                "$mainUrl/$path${sep}page=$currentPage"
            }
        } else {
            val cleanPath = path.trimEnd('/')
            if (currentPage <= 1) {
                "$mainUrl/$cleanPath/"
            } else {
                "$mainUrl/$cleanPath/page/$currentPage/"
            }
        }

        val doc = try {
            app.get(url, headers = defaultHeaders).document
        } catch (_: Exception) {
            return newHomePageResponse(emptyList(), false)
        }

        val items = parseCards(doc)
        val hasNext = items.isNotEmpty() && hasNextPage(doc)
        return newHomePageResponse(
            listOf(
                HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = false
                )
            ),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return newSearchResponseList(emptyList(), false)

        val currentPage = page.coerceAtLeast(1)
        val encoded = try {
            URLEncoder.encode(cleanQuery, "UTF-8")
        } catch (_: Exception) {
            cleanQuery
        }

        val searchUrl = if (currentPage <= 1) {
            "$mainUrl/?s=$encoded"
        } else {
            "$mainUrl/page/$currentPage/?s=$encoded"
        }

        val doc = try {
            app.get(searchUrl, headers = defaultHeaders).document
        } catch (_: Exception) {
            return newSearchResponseList(emptyList(), false)
        }

        val items = parseCards(doc)
        val hasNext = items.isNotEmpty() && hasNextPage(doc)
        return newSearchResponseList(items, hasNext = hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        var pageUrl = fixUrl(url)
        var doc = try {
            app.get(pageUrl, headers = defaultHeaders).document
        } catch (_: Exception) {
            return null
        }

        // If an episode URL was opened directly, resolve its parent series page when present
        if (!pageUrl.contains("/series/")) {
            val parentSeriesUrl = doc.selectFirst("div.nvs.nvsc a[href*='/series/']")?.attr("href")?.trim()
                ?: doc.selectFirst("div.ts-breadcrumb a[href*='/series/']")?.attr("href")?.trim()
            if (!parentSeriesUrl.isNullOrBlank()) {
                val resolvedUrl = fixUrl(parentSeriesUrl)
                val seriesDoc = try {
                    app.get(resolvedUrl, headers = defaultHeaders).document
                } catch (_: Exception) {
                    null
                }
                if (seriesDoc != null) {
                    pageUrl = resolvedUrl
                    doc = seriesDoc
                }
            }
        }

        val title = doc.selectFirst("div.infox h1.entry-title")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("h1.entry-title")?.text()?.trim()?.ifBlank { null }
            ?: return null

        val poster = doc.selectFirst("div.thumbook div.thumb img").extractImageUrl()
            ?: doc.selectFirst("div.tb img").extractImageUrl()

        val synopsis = doc.select("div.bixbox.synp div.entry-content p")
            .mapNotNull { it.text().trim().takeIf { txt -> txt.isNotBlank() } }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }

        var statusText = ""
        var typeText = ""
        var releaseYear: Int? = null

        doc.select("div.spe span").forEach { span ->
            val raw = span.text().trim()
            val label = span.selectFirst("b")?.text()?.trim()?.trimEnd(':')?.lowercase().orEmpty()
            val value = raw.substringAfter(":", "").trim()
            when (label) {
                "status" -> statusText = value
                "type" -> typeText = value
                "released" -> {
                    if (releaseYear == null) {
                        releaseYear = Regex("\\b(19|20)\\d{2}\\b").find(value)?.value?.toIntOrNull()
                    }
                }
            }
        }

        val ratingVal = doc.selectFirst("div.rating meta[itemprop=ratingValue]")
            ?.attr("content")?.trim()
            ?: doc.selectFirst("div.rating strong")?.text()?.removePrefix("Rating")?.trim()
        val parsedScore = Score.from10(ratingVal)

        val resolvedStatus = when {
            statusText.contains("Completed", ignoreCase = true) -> ShowStatus.Completed
            statusText.contains("Ongoing", ignoreCase = true) -> ShowStatus.Ongoing
            else -> null
        }

        val genres = doc.select("div.genxed a")
            .mapNotNull { it.text().trim().takeIf { g -> g.isNotBlank() } }
            .distinct()

        val recommendations = doc.select("div.bixbox")
            .firstOrNull { box ->
                box.selectFirst("div.releases")?.text()?.contains("Recommend", ignoreCase = true) == true
            }
            ?.let { parseCards(it) }
            .orEmpty()

        val epLinks = doc.select("div.eplister ul li a[href]")
        val tvType = when {
            typeText.equals("Movie", ignoreCase = true) -> TvType.AnimeMovie
            typeText.equals("OVA", ignoreCase = true) ||
                typeText.equals("ONA", ignoreCase = true) ||
                typeText.equals("Special", ignoreCase = true) -> TvType.OVA
            else -> TvType.Anime
        }

        if (tvType == TvType.AnimeMovie && epLinks.size <= 1) {
            val movieWatchUrl = epLinks.firstOrNull()?.attr("href")?.trim()?.let { fixUrl(it) } ?: pageUrl
            return newMovieLoadResponse(title, pageUrl, TvType.AnimeMovie, movieWatchUrl) {
                posterUrl = poster
                plot = synopsis
                year = releaseYear
                score = parsedScore
                tags = genres.takeIf { it.isNotEmpty() }
                this.recommendations = recommendations
            }
        }

        val dubStatus = resolveDubStatus(title, epLinks.firstOrNull()?.text().orEmpty())

        // div.eplister lists episodes newest-first; reverse to restore ascending order
        val episodes = epLinks.reversed().mapIndexedNotNull { index, a ->
            val epHref = a.attr("href").trim()
            if (epHref.isBlank()) return@mapIndexedNotNull null
            val epUrl = fixUrl(epHref)
            val numText = a.selectFirst("div.epl-num")?.text()?.trim().orEmpty()
            val epTitle = a.selectFirst("div.epl-title")?.text()?.trim()?.ifBlank { null }
                ?: "Episode ${numText.ifBlank { (index + 1).toString() }}"
            val epNum = Regex("(\\d+)").find(numText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("\\bEpisode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: (index + 1)

            newEpisode(epUrl) {
                this.name = epTitle
                this.season = 1
                this.episode = epNum
                this.posterUrl = poster
            }
        }

        return newAnimeLoadResponse(title, pageUrl, tvType) {
            posterUrl = poster
            plot = synopsis
            year = releaseYear
            score = parsedScore
            tags = genres.takeIf { it.isNotEmpty() }
            showStatus = resolvedStatus
            this.recommendations = recommendations
            addEpisodes(dubStatus, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epUrl = fixUrl(data)
        val doc = try {
            app.get(epUrl, headers = defaultHeaders).document
        } catch (_: Exception) {
            return false
        }

        var foundAny = false
        val seenEmbeds = mutableSetOf<String>()

        // 1. Primary active iframe in #pembed / .video-content
        doc.select("div#pembed iframe[src], div.video-content iframe[src]").forEach { iframe ->
            normalizeUrl(iframe.attr("src"))?.let { seenEmbeds.add(it) }
        }

        // 2. Base64-encoded mirror options in select.mirror
        doc.select("select.mirror option[value]").forEach { option ->
            val b64 = option.attr("value").trim()
            if (b64.isBlank()) return@forEach
            val decodedHtml = decodeBase64(b64)
            if (decodedHtml.isBlank()) return@forEach
            val frag = Jsoup.parseBodyFragment(decodedHtml, mainUrl)
            frag.select("iframe[src]").forEach { iframe ->
                normalizeUrl(iframe.attr("src"))?.let { seenEmbeds.add(it) }
            }
        }

        for (embedUrl in seenEmbeds) {
            if (extractFromEmbed(embedUrl, epUrl, subtitleCallback, callback)) {
                foundAny = true
            }
        }

        // 3. Direct download hosts (e.g., GoFile) from .mobius icon bar
        val dlLinks = doc.select("div.mobius a[href]")
            .mapNotNull { normalizeUrl(it.attr("href")) }
            .filterNot { it.startsWith(mainUrl, ignoreCase = true) }
            .distinct()

        for (dlUrl in dlLinks) {
            if (loadExtractor(dlUrl, epUrl, subtitleCallback, callback)) {
                foundAny = true
            }
        }

        return foundAny
    }

    private suspend fun extractFromEmbed(
        embedUrl: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Case A: Query-parameter m3u8 / subtitle player (e.g., player.php?m3u8=...&subtitle=...)
        if (embedUrl.contains("m3u8=", ignoreCase = true)) {
            val m3u8Param = extractQueryParam(embedUrl, "m3u8")?.let { normalizeUrl(it) }
            if (m3u8Param != null) {
                val origin = extractOrigin(embedUrl)
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name HLS",
                        url = m3u8Param,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = origin?.let { "$it/" } ?: refererUrl
                    }
                )
                val subParam = extractQueryParam(embedUrl, "subtitle")?.let { normalizeUrl(it) }
                if (subParam != null) {
                    subtitleCallback(
                        newSubtitleFile(
                            lang = "English",
                            url = subParam
                        )
                    )
                }
                return true
            }
        }

        // Case B: UpBolt embed (https://upbolt.to/e/<code>)
        if (embedUrl.contains("upbolt.to/", ignoreCase = true)) {
            if (extractUpBolt(embedUrl, callback)) {
                return true
            }
        }

        // Case C: Blogger video.g player (https://www.blogger.com/video.g?token=...)
        if (embedUrl.contains("blogger.com/video.g", ignoreCase = true)) {
            if (extractBlogger(embedUrl, refererUrl, callback)) {
                return true
            }
        }

        // Case D: Delegate to built-in CloudStream extractors (e.g., VkSpeed, GoFile, etc.)
        return loadExtractor(embedUrl, refererUrl, subtitleCallback, callback)
    }

    private suspend fun extractUpBolt(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val code = embedUrl.substringBefore("?").trimEnd('/')
            .substringAfterLast("/")
            .removePrefix("emb-")
            .removeSuffix(".html")
            .trim()
        if (code.isBlank()) return false

        val origin = extractOrigin(embedUrl) ?: "https://upbolt.to"
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$origin/"
        )

        var html = try {
            app.get("$origin/emb-$code", headers = headers).text
        } catch (_: Exception) {
            ""
        }

        val m3u8Regex = Regex("""https?://[^\s"'<>]+\.m3u8(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
        var m3u8Urls = m3u8Regex.findAll(html).map { it.value }.distinct().toList()

        if (m3u8Urls.isEmpty()) {
            html = try {
                app.post(
                    "$origin/dl",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to embedUrl
                    ),
                    data = mapOf(
                        "op" to "embed",
                        "file_code" to code,
                        "auto" to "1",
                        "referer" to "$mainUrl/"
                    )
                ).text
            } catch (_: Exception) {
                ""
            }
            m3u8Urls = m3u8Regex.findAll(html).map { it.value }.distinct().toList()
        }

        if (m3u8Urls.isEmpty()) return false

        for (m3u8 in m3u8Urls) {
            callback(
                newExtractorLink(
                    source = "UpBolt",
                    name = "UpBolt",
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$origin/"
                }
            )
        }
        return true
    }

    private suspend fun extractBlogger(
        embedUrl: String,
        refererUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawHtml = try {
            app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to refererUrl
                )
            ).text
        } catch (_: Exception) {
            return false
        }

        val unescaped = Parser.unescapeEntities(rawHtml, false)
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
            .replace("\\/", "/")

        val streamRegex = Regex(
            """https://[^"'\s\\]+googlevideo\.com/videoplayback\?[^"'\s\\]+""",
            RegexOption.IGNORE_CASE
        )

        val candidates = streamRegex.findAll(unescaped)
            .map { it.value }
            .distinct()
            .filter { it.contains("itag=", ignoreCase = true) }
            .toList()

        if (candidates.isEmpty()) return false

        for (videoUrl in candidates) {
            val itag = Regex("[?&]itag=(\\d+)").find(videoUrl)?.groupValues?.get(1)
            val qualityLabel = when (itag) {
                "18" -> "360p"
                "22" -> "720p"
                "37", "137" -> "1080p"
                else -> "SD"
            }
            callback(
                newExtractorLink(
                    source = "Blogger",
                    name = "Blogger $qualityLabel",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://www.blogger.com/"
                    this.quality = getQualityFromName(qualityLabel)
                }
            )
        }
        return true
    }

    private fun parseCards(root: Element): List<SearchResponse> {
        return root.select("article.bs").mapNotNull { article ->
            val linkEl = article.selectFirst("div.bsx a[href]") ?: return@mapNotNull null
            val href = linkEl.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null

            val itemUrl = fixUrl(href)
            val title = article.selectFirst("div.tt h2")?.text()?.trim()?.ifBlank { null }
                ?: linkEl.attr("title").trim().ifBlank { null }
                ?: return@mapNotNull null

            val poster = article.selectFirst("div.limit img").extractImageUrl()
            val typeLabel = article.selectFirst("div.typez")?.text()?.trim().orEmpty()
            val subBadge = article.selectFirst("span.sb")?.text()?.trim().orEmpty()
            val epBadge = article.selectFirst("span.epx")?.text()?.trim().orEmpty()
            val epCount = Regex("\\bEp\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(epBadge)?.groupValues?.get(1)?.toIntOrNull()
            val dubStatus = resolveDubStatus(title, subBadge)

            when {
                typeLabel.equals("Movie", ignoreCase = true) -> {
                    newAnimeSearchResponse(title, itemUrl, TvType.AnimeMovie) {
                        posterUrl = poster
                        addDubStatus(dubStatus)
                    }
                }
                typeLabel.equals("OVA", ignoreCase = true) ||
                    typeLabel.equals("ONA", ignoreCase = true) ||
                    typeLabel.equals("Special", ignoreCase = true) -> {
                    newAnimeSearchResponse(title, itemUrl, TvType.OVA) {
                        posterUrl = poster
                        addDubStatus(dubStatus, epCount)
                    }
                }
                else -> {
                    newAnimeSearchResponse(title, itemUrl, TvType.Anime) {
                        posterUrl = poster
                        addDubStatus(dubStatus, epCount)
                    }
                }
            }
        }.distinctBy { it.url }
    }

    private fun hasNextPage(doc: Document): Boolean {
        return doc.selectFirst("div.hpage a.r") != null ||
            doc.selectFirst("div.pagination a.next.page-numbers") != null
    }

    private fun resolveDubStatus(title: String, badge: String): DubStatus {
        val combined = "$title $badge"
        return when {
            combined.contains("Dub", ignoreCase = true) -> DubStatus.Dubbed
            combined.contains("Sub", ignoreCase = true) -> DubStatus.Subbed
            else -> DubStatus.None
        }
    }

    private fun Element?.extractImageUrl(): String? {
        if (this == null) return null
        val raw = listOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("src")
        ).firstOrNull { candidate ->
            candidate.isNotBlank() && !candidate.startsWith("data:image", ignoreCase = true)
        }?.trim() ?: return null
        return fixUrlNull(raw)
    }

    private fun normalizeUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> null
        }
    }

    private fun extractOrigin(url: String): String? {
        return try {
            val uri = URI(url)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            if (uri.port > 0) "$scheme://$host:${uri.port}" else "$scheme://$host"
        } catch (_: Exception) {
            null
        }
    }

    private fun extractQueryParam(url: String, key: String): String? {
        val query = url.substringAfter("?", "")
        if (query.isBlank()) return null
        for (pair in query.split("&")) {
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2 && parts[0].equals(key, ignoreCase = true)) {
                return try {
                    URLDecoder.decode(parts[1], "UTF-8")
                } catch (_: Exception) {
                    parts[1]
                }
            }
        }
        return null
    }

    private fun decodeBase64(input: String): String {
        val cleaned = input.trim()
            .filter { !it.isWhitespace() }
            .replace('-', '+')
            .replace('_', '/')
        if (cleaned.isEmpty()) return ""
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val output = ArrayList<Byte>((cleaned.length * 3) / 4)
        var buffer = 0
        var bitsCollected = 0
        for (ch in cleaned) {
            if (ch == '=') break
            val value = alphabet.indexOf(ch)
            if (value < 0) continue
            buffer = (buffer shl 6) or value
            bitsCollected += 6
            if (bitsCollected >= 8) {
                bitsCollected -= 8
                output.add(((buffer shr bitsCollected) and 0xFF).toByte())
            }
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }
}
