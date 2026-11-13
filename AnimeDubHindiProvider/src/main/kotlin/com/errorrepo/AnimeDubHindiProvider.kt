package com.errorrepo

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class AnimeDubHindiProvider : MainAPI() {
    override var mainUrl = "https://www.animedubhindi.link"
    override var name = "AnimeDubHindi"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    private val mapper = jacksonObjectMapper()
    private val adhlinksBase = "https://new.adhlinks.com"

    private val defaultHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    private data class ParsedLink(
        val quality: String,
        val label: String,
        val url: String
    )

    private data class ParsedEpisodeBlock(
        val title: String,
        val episodeNumber: Int?,
        val links: List<ParsedLink>
    )

    override val mainPage = mainPageOf(
        "" to "Latest Releases",
        "category/series" to "Anime Series",
        "category/movie" to "Anime Movies",
        "category/hindi-dubbed" to "Hindi Dubbed",
        "category/official" to "Official Hindi Dubs",
        "category/ongoing" to "Ongoing",
        "category/completed" to "Completed",
        "category/action" to "Action",
        "category/adventure" to "Adventure",
        "category/comedy" to "Comedy",
        "category/fantasy" to "Fantasy",
        "category/isekai" to "Isekai",
        "category/romance" to "Romance",
        "category/shounen" to "Shounen",
        "category/donghua" to "Donghua"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val currentPage = page.coerceAtLeast(1)
        val cleanPath = request.data.trim('/')
        val url = when {
            cleanPath.isEmpty() && currentPage <= 1 -> "$mainUrl/"
            cleanPath.isEmpty() -> "$mainUrl/page/$currentPage/"
            currentPage <= 1 -> "$mainUrl/$cleanPath/"
            else -> "$mainUrl/$cleanPath/page/$currentPage/"
        }

        val doc = try {
            app.get(url, headers = defaultHeaders).document
        } catch (_: Exception) {
            return newHomePageResponse(emptyList(), false)
        }

        val items = parseArchiveCards(doc)
        val hasNext = items.isNotEmpty() && hasNextPage(doc, currentPage)

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

        val items = parseArchiveCards(doc)
        val hasNext = items.isNotEmpty() && hasNextPage(doc, currentPage)
        return newSearchResponseList(items, hasNext = hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = try {
            app.get(pageUrl, headers = defaultHeaders).document
        } catch (_: Exception) {
            return null
        }

        val rawTitle = doc.selectFirst("h1.entry-title, h1")?.text()?.trim()?.ifBlank { null }
            ?: return null

        val contentEl = doc.selectFirst("div.entry-content") ?: return null

        val poster = contentEl.selectFirst("figure.wp-block-image img").extractImageUrl()
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.ifBlank { null }
            ?: contentEl.selectFirst("img").extractImageUrl()

        val synopsis = contentEl.select("p")
            .firstOrNull { it.text().contains("Synopsis:", ignoreCase = true) }
            ?.text()
            ?.replace(Regex("^\\s*Synopsis\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.ifBlank { null }

        var infoTitle: String? = null
        var ratingText: String? = null
        val genres = mutableListOf<String>()
        var audioText = ""

        contentEl.select("ul li").forEach { li ->
            val rawLine = li.text().replace('\u00A0', ' ').trim()
            val colonIdx = rawLine.indexOf(':')
            if (colonIdx <= 0) return@forEach
            val label = rawLine.substring(0, colonIdx).trim()
            val value = rawLine.substring(colonIdx + 1).trim()
            if (value.isBlank()) return@forEach

            when {
                label.equals("Title", ignoreCase = true) -> {
                    infoTitle = value
                }
                label.contains("Rating", ignoreCase = true) -> {
                    ratingText = value.substringBefore("/").trim()
                }
                label.contains("Genre", ignoreCase = true) -> {
                    value.split("|", ",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { genres.add(it) }
                }
                label.contains("Audio", ignoreCase = true) -> {
                    audioText = value
                }
            }
        }

        val displayTitle = infoTitle?.takeIf { it.isNotBlank() } ?: cleanPostTitle(rawTitle)
        val releaseYear = Regex("\\b(19|20)\\d{2}\\b")
            .find("$displayTitle $rawTitle")
            ?.value?.toIntOrNull()
        val parsedScore = Score.from10(ratingText)

        val adhlinksUrl = contentEl.select("a[href*='adhlinks']")
            .mapNotNull { it.attr("href").trim().ifBlank { null } }
            .firstOrNull()
            ?: return null

        val adhHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to pageUrl
        )

        val adhDoc = try {
            app.get(adhlinksUrl, headers = adhHeaders).document
        } catch (_: Exception) {
            return null
        }

        val adhOrigin = extractOrigin(adhlinksUrl) ?: adhlinksBase
        val articleEl = doc.selectFirst("article")
        val isMovie = adhlinksUrl.contains("/movie/", ignoreCase = true) ||
            articleEl?.hasClass("category-movie") == true ||
            rawTitle.contains("Movie Download", ignoreCase = true)

        val dubStatus = resolveDubStatus("$audioText $rawTitle")

        if (isMovie) {
            val movieLinks = parseMovieLinks(adhDoc, adhOrigin)
            if (movieLinks.isEmpty()) return null
            val moviePayload = encodeLinkPayload(movieLinks)

            return newMovieLoadResponse(displayTitle, pageUrl, TvType.AnimeMovie, moviePayload) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = synopsis
                year = releaseYear
                score = parsedScore
                tags = genres.distinct().takeIf { it.isNotEmpty() }
            }
        }

        val seasonNumber = extractSeasonNumber("$displayTitle $rawTitle")
        val parsedEpisodes = parseSeriesEpisodes(adhDoc, adhOrigin)
        if (parsedEpisodes.isEmpty()) return null

        val episodes = parsedEpisodes
            .sortedWith(compareBy({ it.episodeNumber ?: Int.MAX_VALUE }, { it.title }))
            .mapIndexed { idx, epBlock ->
                val epNum = epBlock.episodeNumber ?: (idx + 1)
                val cleanEpName = epBlock.title
                    .replace(Regex("\\[New\\]", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("^Episode\\s*:\\s*", RegexOption.IGNORE_CASE), "Episode ")
                    .trim()
                    .ifBlank { "Episode $epNum" }

                newEpisode(encodeLinkPayload(epBlock.links)) {
                    this.name = cleanEpName
                    this.season = seasonNumber
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }

        val resolvedStatus = when {
            articleEl?.hasClass("category-ongoing") == true -> ShowStatus.Ongoing
            articleEl?.hasClass("category-completed") == true -> ShowStatus.Completed
            else -> null
        }

        return newAnimeLoadResponse(displayTitle, pageUrl, TvType.Anime) {
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = synopsis
            year = releaseYear
            score = parsedScore
            tags = genres.distinct().takeIf { it.isNotEmpty() }
            showStatus = resolvedStatus
            addEpisodes(dubStatus, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = decodeLinkPayload(data)
        if (links.isEmpty()) return false

        var emitted = false
        val seenUrls = mutableSetOf<String>()

        for (item in links) {
            val linkUrl = item.url.trim()
            if (linkUrl.isEmpty() || !seenUrls.add(linkUrl)) continue

            val qualityLabel = item.quality.ifBlank { "Auto" }
            val labelLower = item.label.lowercase()

            when {
                linkUrl.contains("hubcloud", ignoreCase = true) ||
                    labelLower == "hcloud" ||
                    labelLower == "hubcloud" -> {
                    if (resolveHubCloud(linkUrl, qualityLabel, subtitleCallback, callback)) {
                        emitted = true
                    }
                }

                linkUrl.contains("/re.php?", ignoreCase = true) ||
                    linkUrl.contains("filesforever.link", ignoreCase = true) ||
                    linkUrl.contains("pro.iqsmartgames.com", ignoreCase = true) ||
                    labelLower == "multi" -> {
                    if (resolveMultiLink(linkUrl, qualityLabel, callback)) {
                        emitted = true
                    }
                }

                // Skip ad-shortener MEGA links and external index pages
                linkUrl.contains("/redirect.php?", ignoreCase = true) ||
                    linkUrl.contains("desidubanime.me", ignoreCase = true) ||
                    labelLower == "mega" ||
                    labelLower == "click here" -> Unit

                else -> {
                    if (loadExtractor(linkUrl, "$adhlinksBase/", subtitleCallback, callback)) {
                        emitted = true
                    }
                }
            }
        }

        return emitted
    }

    private suspend fun resolveHubCloud(
        driveUrl: String,
        qualityLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val step1Headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$adhlinksBase/"
        )
        val step1Doc = try {
            app.get(driveUrl, headers = step1Headers).document
        } catch (_: Exception) {
            return false
        }

        val hubPhpUrl = step1Doc.select("a[href]")
            .mapNotNull { it.attr("href").trim().ifBlank { null } }
            .firstOrNull {
                it.contains("hubcloud.php", ignoreCase = true) ||
                    it.contains("gamerxyt.com", ignoreCase = true)
            }
            ?: return false

        val step2Headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to driveUrl
        )
        val step2Doc = try {
            app.get(hubPhpUrl, headers = step2Headers).document
        } catch (_: Exception) {
            return false
        }

        val parsedQuality = parseQualityFromText(qualityLabel)
        val step2Origin = extractOrigin(hubPhpUrl) ?: "https://gamerxyt.com"
        var emitted = false
        val emittedStreams = mutableSetOf<String>()

        for (anchor in step2Doc.select("a[href]")) {
            val href = anchor.attr("href").trim()
            if (href.isEmpty()) continue
            val text = anchor.text().trim()

            when {
                href.contains("r2.cloudflarestorage.com", ignoreCase = true) ||
                    (text.contains("FSL", ignoreCase = true) && href.startsWith("http")) -> {
                    if (emittedStreams.add(href)) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name FSL [$qualityLabel]",
                                url = href,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = parsedQuality
                                this.referer = "$step2Origin/"
                            }
                        )
                        emitted = true
                    }
                }

                href.contains("hbplay.pages.dev", ignoreCase = true) -> {
                    val encodedStream = extractQueryParam(href, "u")
                    val decodedStream = encodedStream?.let { decodeBase64(it) }?.trim()
                    if (!decodedStream.isNullOrBlank() &&
                        decodedStream.startsWith("http") &&
                        emittedStreams.add(decodedStream)
                    ) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name HBPlay [$qualityLabel]",
                                url = decodedStream,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = parsedQuality
                                this.referer = "https://hbplay.pages.dev/"
                            }
                        )
                        emitted = true
                    }
                }

                href.contains("pixeldrain.", ignoreCase = true) -> {
                    if (loadExtractor(href, hubPhpUrl, subtitleCallback, callback)) {
                        emitted = true
                    }
                }
            }
        }

        return emitted
    }

    private suspend fun resolveMultiLink(
        rawUrl: String,
        qualityLabel: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var targetUrl = rawUrl
        val reHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$adhlinksBase/"
        )

        if (targetUrl.contains("/re.php?", ignoreCase = true)) {
            val reHtml = try {
                app.get(targetUrl, headers = reHeaders).text
            } catch (_: Exception) {
                return false
            }
            val b64 = Regex("""var\s+redirectUrl\s*=\s*["']([^"']+)["']""")
                .find(reHtml)?.groupValues?.get(1)
                ?: return false
            targetUrl = decodeBase64(b64).trim()
            if (!targetUrl.startsWith("http")) return false
        }

        val pageResponse = try {
            app.get(targetUrl, headers = reHeaders)
        } catch (_: Exception) {
            return false
        }

        val pageHtml = pageResponse.text
        val rawFileUrl = Regex("""const\s+fileurl\s*=\s*["']([^"']+)["']""")
            .find(pageHtml)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.trim()
            ?: return false

        if (!rawFileUrl.startsWith("http")) return false

        val origin = extractOrigin(pageResponse.url) ?: "https://pro.iqsmartgames.com"
        val parsedQuality = parseQualityFromText(qualityLabel)

        callback(
            newExtractorLink(
                source = name,
                name = "$name Multi [$qualityLabel]",
                url = rawFileUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.quality = parsedQuality
                this.referer = "$origin/"
            }
        )
        return true
    }

    private fun parseMovieLinks(doc: Document, origin: String): List<ParsedLink> {
        val collected = mutableListOf<ParsedLink>()

        doc.select("div.pro-ep-card").forEach { card ->
            if (card.selectFirst("div.pro-zip-title") != null) return@forEach
            card.select("div.pro-quality-wrapper").forEach { wrapper ->
                val quality = wrapper.selectFirst("span.pro-ep-quality")?.text()?.trim()
                    ?.removeSurrounding("[", "]")?.trim().orEmpty()
                wrapper.select("div.pro-btn-group a[href]").forEach { a ->
                    val label = a.text().trim()
                    val href = resolveRelativeUrl(a.attr("href"), origin)
                    if (label.isNotEmpty() && href != null) {
                        collected.add(ParsedLink(quality = quality, label = label, url = href))
                    }
                }
            }
        }

        if (collected.isNotEmpty()) return collected

        // Fallback for block-heading movie pages
        doc.select("div.nv-content-wrap h4, div.entry-content h4").forEach { h4 ->
            collected.addAll(parseInlineQualityLinks(h4, origin))
        }
        return collected
    }

    private fun parseSeriesEpisodes(doc: Document, origin: String): List<ParsedEpisodeBlock> {
        // Layout A: .pro-ep-card containers
        val cardEpisodes = doc.select("div.pro-ep-card").mapNotNull { card ->
            if (card.selectFirst("div.pro-zip-title") != null) return@mapNotNull null
            val epTitle = card.selectFirst("h4.pro-ep-title")?.text()?.trim()
                ?: return@mapNotNull null
            if (epTitle.contains("Zip Pack", ignoreCase = true)) return@mapNotNull null

            val epNum = Regex("(?:Episode|Ep)\\s*[:\\-]?\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            val links = mutableListOf<ParsedLink>()
            card.select("div.pro-quality-wrapper").forEach { wrapper ->
                val quality = wrapper.selectFirst("span.pro-ep-quality")?.text()?.trim()
                    ?.removeSurrounding("[", "]")?.trim().orEmpty()
                wrapper.select("div.pro-btn-group a[href]").forEach { a ->
                    val label = a.text().trim()
                    val href = resolveRelativeUrl(a.attr("href"), origin)
                    if (label.isNotEmpty() && href != null) {
                        links.add(ParsedLink(quality = quality, label = label, url = href))
                    }
                }
            }

            if (links.isEmpty()) return@mapNotNull null
            ParsedEpisodeBlock(title = epTitle, episodeNumber = epNum, links = links)
        }

        if (cardEpisodes.isNotEmpty()) return cardEpisodes

        // Layout B: WordPress wp-block-group containers with h2 + h4
        val seenTitles = mutableSetOf<String>()
        return doc.select("div.wp-block-group").mapNotNull { group ->
            val heading = group.selectFirst("h2.wp-block-heading, h2") ?: return@mapNotNull null
            val epTitle = heading.text().trim()
            if (!epTitle.contains("Episode", ignoreCase = true)) return@mapNotNull null
            if (!seenTitles.add(epTitle)) return@mapNotNull null

            val epNum = Regex("(?:Episode|Ep)\\s*[:\\-]?\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            val bodyEl = group.selectFirst("h4.wp-block-heading, h4") ?: return@mapNotNull null
            val links = parseInlineQualityLinks(bodyEl, origin)
            if (links.isEmpty()) return@mapNotNull null

            ParsedEpisodeBlock(title = epTitle, episodeNumber = epNum, links = links)
        }
    }

    private fun parseInlineQualityLinks(container: Element, origin: String): List<ParsedLink> {
        val links = mutableListOf<ParsedLink>()
        var currentQuality = ""

        for (node in container.childNodes()) {
            when (node) {
                is TextNode -> {
                    val text = node.text().replace('\u00A0', ' ').trim()
                    if (text.contains("Watch online", ignoreCase = true)) {
                        continue
                    }
                    val qualityMatch = Regex(
                        "(\\d{3,4}p(?:\\s*x26[45])?(?:\\s*10bit)?(?:\\s*HQ)?)",
                        RegexOption.IGNORE_CASE
                    ).find(text)
                    if (qualityMatch != null) {
                        currentQuality = qualityMatch.groupValues[1].trim()
                    }
                }
                is Element -> {
                    if (node.tagName().equals("a", ignoreCase = true)) {
                        val label = node.text().trim()
                        val href = resolveRelativeUrl(node.attr("href"), origin)
                        if (label.isNotEmpty() && href != null) {
                            links.add(ParsedLink(quality = currentQuality, label = label, url = href))
                        }
                    }
                }
            }
        }
        return links
    }

    private fun parseArchiveCards(doc: Document): List<SearchResponse> {
        return doc.select("article.post, article[id^=post-]").mapNotNull { article ->
            val titleAnchor = article.selectFirst("h2.entry-title a") ?: return@mapNotNull null
            val rawTitle = titleAnchor.text().trim()
            val href = titleAnchor.attr("href").trim()
            if (rawTitle.isEmpty() || href.isEmpty()) return@mapNotNull null

            val itemUrl = fixUrl(href)
            val displayTitle = cleanPostTitle(rawTitle)
            val poster = article.selectFirst("div.post-thumb img").extractImageUrl()
            val isMovie = article.hasClass("category-movie") ||
                rawTitle.contains("Movie Download", ignoreCase = true)
            val dubStatus = resolveDubStatus(rawTitle)

            if (isMovie) {
                newAnimeSearchResponse(displayTitle, itemUrl, TvType.AnimeMovie) {
                    posterUrl = poster
                    addDubStatus(dubStatus)
                }
            } else {
                newAnimeSearchResponse(displayTitle, itemUrl, TvType.Anime) {
                    posterUrl = poster
                    addDubStatus(dubStatus)
                }
            }
        }.distinctBy { it.url }
    }

    private fun hasNextPage(doc: Document, currentPage: Int): Boolean {
        if (doc.selectFirst("a.next.page-numbers, nav.navigation a.next") != null) {
            return true
        }
        return doc.select("ul.page-numbers a[href], div.nav-links a[href]").any { a ->
            val pageNum = Regex("/page/(\\d+)/?")
                .find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            pageNum != null && pageNum > currentPage
        }
    }

    private fun encodeLinkPayload(links: List<ParsedLink>): String {
        val array = mapper.createArrayNode()
        links.forEach { link ->
            val obj = mapper.createObjectNode()
            obj.put("q", link.quality)
            obj.put("l", link.label)
            obj.put("u", link.url)
            array.add(obj)
        }
        return mapper.writeValueAsString(array)
    }

    private fun decodeLinkPayload(data: String): List<ParsedLink> {
        val trimmed = data.trim()
        if (trimmed.startsWith("[")) {
            return try {
                val root = mapper.readTree(trimmed)
                root.mapNotNull { node ->
                    val url = node.path("u").asText("").trim()
                    if (url.isEmpty()) null else ParsedLink(
                        quality = node.path("q").asText(""),
                        label = node.path("l").asText(""),
                        url = url
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        if (trimmed.startsWith("http")) {
            return listOf(ParsedLink(quality = "", label = "", url = trimmed))
        }
        return emptyList()
    }

    private fun cleanPostTitle(rawTitle: String): String {
        var cleaned = rawTitle.replace('\u00A0', ' ').trim()
        cleaned = cleaned.replace(
            Regex("\\s+(?:in\\s+)?(?:Hindi|Dual\\s*Audio|Multi\\s*Audio)\\b.*$", RegexOption.IGNORE_CASE),
            ""
        )
        cleaned = cleaned.replace(
            Regex("\\s*\\((?:Dual|Multi)\\s*Audio\\).*$", RegexOption.IGNORE_CASE),
            ""
        )
        cleaned = cleaned.replace(
            Regex("\\s+(?:BluRay|NF\\s+WEB-DL|WEB-DL|WEBRip|HDRip|HDTV|Episodes?\\s+Download|Movie\\s+Download).*$", RegexOption.IGNORE_CASE),
            ""
        )
        cleaned = cleaned.trim(' ', '-', '–', ':')
        return cleaned.ifBlank { rawTitle.trim() }
    }

    private fun extractSeasonNumber(text: String): Int {
        val match = Regex("(?:Season|S)\\s*0*(\\d+)", RegexOption.IGNORE_CASE).find(text)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    private fun resolveDubStatus(text: String): DubStatus {
        return when {
            text.contains("Hindi", ignoreCase = true) ||
                text.contains("Dub", ignoreCase = true) -> DubStatus.Dubbed
            text.contains("Sub", ignoreCase = true) -> DubStatus.Subbed
            else -> DubStatus.None
        }
    }

    private fun parseQualityFromText(qualityText: String): Int {
        val resMatch = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE).find(qualityText)
        return if (resMatch != null) {
            getQualityFromName("${resMatch.groupValues[1]}p")
        } else {
            getQualityFromName(qualityText)
        }
    }

    private fun resolveRelativeUrl(rawHref: String, origin: String): String? {
        val trimmed = rawHref.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("javascript:", ignoreCase = true)) {
            return null
        }
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "${origin.trimEnd('/')}$trimmed"
            else -> "${origin.trimEnd('/')}/$trimmed"
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
        val regex = Regex("[?&]${Regex.escape(key)}=([^&#]+)")
        val raw = regex.find(url)?.groupValues?.get(1) ?: return null
        return try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            raw
        }
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
