package com.errorrepo

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class AnimeDriveProvider : MainAPI() {
    override var mainUrl = "https://animedrive.in"
    override var name = "AnimeDrive"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    private val mapper = jacksonObjectMapper()
    private val linkHubBase = "https://link.animedrive.in"
    private val defaultHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    private data class ParsedLink(
        val quality: String,
        val host: String,
        val label: String,
        val url: String
    )

    private data class ParsedEpisode(
        val episodeNumber: Int?,
        val title: String,
        val links: List<ParsedLink>
    )

    override val mainPage = mainPageOf(
        "" to "Latest Releases",
        "category/hindi-dubbed-anime" to "Hindi Dubbed",
        "category/dual-audio-anime" to "Dual Audio / Multi",
        "category/english-dub-anime-download" to "English Dubbed",
        "category/english-sub-anime-download" to "English Subbed",
        "category/tamil-dub-anime-download" to "Tamil Dubbed",
        "category/telugu-dub-anime-download" to "Telugu Dubbed",
        "category/on-going" to "On-Going",
        "category/movies" to "Anime Movies",
        "category/action" to "Action",
        "category/adventure" to "Adventure",
        "category/fantasy" to "Fantasy",
        "category/comedy" to "Comedy",
        "category/drama" to "Drama",
        "category/dark-fantasy" to "Dark Fantasy",
        "category/romance" to "Romance",
        "category/isekai" to "Isekai",
        "category/mystery" to "Mystery",
        "category/shounen" to "Shounen",
        "category/supernatural" to "Supernatural",
        "category/horror" to "Horror",
        "category/slice-of-life" to "Slice of Life",
        "category/sci-fi" to "Sci-Fi"
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
            return newHomePageResponse(emptyList(), hasNext = false)
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
        if (cleanQuery.isEmpty()) return newSearchResponseList(emptyList(), hasNext = false)

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
            return newSearchResponseList(emptyList(), hasNext = false)
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
        val articleEl = doc.selectFirst("article")
        val contentEl = doc.selectFirst("div.entry-content") ?: doc.body()

        val poster = contentEl.selectFirst("img.wp-post-image, figure img, p img").extractImageUrl()
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.ifBlank { null }
            ?: contentEl.selectFirst("img").extractImageUrl()

        var infoTitle: String? = null
        var seasonText: String? = null
        var episodesOrStatusText: String? = null
        var yearText: String? = null
        var ratingText: String? = null
        var audioText = ""
        val genres = mutableListOf<String>()

        contentEl.select("ul li").forEach { li ->
            val rawLine = li.text().replace('\u00A0', ' ').trim()
            val colonIdx = rawLine.indexOf(':')
            if (colonIdx <= 0) return@forEach

            val rawLabel = rawLine.substring(0, colonIdx)
            val label = rawLabel.replace(Regex("[^A-Za-z ]"), "").trim()
            val value = cleanInfoValue(rawLine.substring(colonIdx + 1))
            if (value.isBlank()) return@forEach

            when {
                label.equals("Series Name", ignoreCase = true) ||
                    label.equals("Title", ignoreCase = true) -> {
                    infoTitle = value
                }
                label.equals("Season", ignoreCase = true) -> {
                    seasonText = value
                }
                label.equals("Status", ignoreCase = true) ||
                    label.equals("Episodes", ignoreCase = true) -> {
                    episodesOrStatusText = value
                }
                label.equals("Year", ignoreCase = true) -> {
                    yearText = value
                }
                label.contains("Genre", ignoreCase = true) -> {
                    value.split(",", "|")
                        .map { cleanInfoValue(it) }
                        .filter { it.isNotEmpty() }
                        .forEach { genres.add(it) }
                }
                label.contains("Language", ignoreCase = true) ||
                    label.contains("Audio", ignoreCase = true) -> {
                    audioText = value
                }
                label.contains("Rating", ignoreCase = true) -> {
                    val candidate = value.substringBefore("/").replace("⭐", "").trim()
                    if (!candidate.equals("N/A", ignoreCase = true) &&
                        !candidate.contains("TBD", ignoreCase = true)
                    ) {
                        ratingText = candidate
                    }
                }
            }
        }

        val displayTitle = cleanPostTitle(infoTitle?.takeIf { it.isNotBlank() } ?: rawTitle)
        val releaseYear = Regex("\\b(19|20)\\d{2}\\b")
            .find(yearText ?: "$displayTitle $rawTitle")
            ?.value?.toIntOrNull()
        val parsedScore = ratingText?.let { Score.from10(it) }
        val plotText = extractPlot(contentEl)
        val trailerUrl = extractTrailerUrl(contentEl)

        val linkHubUrl = contentEl.selectFirst("a.anime-dl-btn[href*='link.animedrive.in']")
            ?.attr("href")?.trim()?.ifBlank { null }
            ?: contentEl.selectFirst("a[href*='link.animedrive.in']")
                ?.attr("href")?.trim()?.ifBlank { null }
            ?: return null

        val linkHubDoc = fetchLinkHubDocument(linkHubUrl, pageUrl) ?: return null
        val linkHubOrigin = extractOrigin(linkHubUrl) ?: linkHubBase

        val isMovie = articleEl?.hasClass("category-movies") == true ||
            seasonText?.contains("Movie", ignoreCase = true) == true ||
            episodesOrStatusText?.contains("Movie", ignoreCase = true) == true ||
            rawTitle.contains("Full Movie", ignoreCase = true) ||
            Regex("\\bMovie\\b", RegexOption.IGNORE_CASE).containsMatchIn(rawTitle)

        val dubStatus = resolveDubStatus("$audioText $rawTitle")

        if (isMovie) {
            val movieLinks = parseMovieLinks(linkHubDoc, linkHubOrigin)
            if (movieLinks.isEmpty()) return null
            return newMovieLoadResponse(
                displayTitle,
                pageUrl,
                TvType.AnimeMovie,
                encodeLinkPayload(movieLinks)
            ) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = plotText
                year = releaseYear
                score = parsedScore
                tags = genres.distinct().takeIf { it.isNotEmpty() }
                trailerUrl?.let { addTrailer(listOf(it)) }
            }
        }

        val parsedEpisodes = parseSeriesEpisodes(linkHubDoc, linkHubOrigin)
        if (parsedEpisodes.isEmpty()) return null

        val seasonNumber = extractSeasonNumber(seasonText ?: "$displayTitle $rawTitle")
        val resolvedStatus = when {
            articleEl?.hasClass("category-on-going") == true -> ShowStatus.Ongoing
            rawTitle.contains("Complete Season Pack", ignoreCase = true) -> ShowStatus.Completed
            Regex("\\bEP-?\\d+\\s+Added\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn("$rawTitle ${episodesOrStatusText.orEmpty()}") -> ShowStatus.Ongoing
            else -> null
        }

        val episodes = parsedEpisodes
            .sortedWith(compareBy({ it.episodeNumber ?: Int.MAX_VALUE }, { it.title }))
            .mapIndexed { idx, ep ->
                val epNum = ep.episodeNumber ?: (idx + 1)
                newEpisode(encodeLinkPayload(ep.links)) {
                    this.name = ep.title.ifBlank { "Episode $epNum" }
                    this.season = seasonNumber
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }

        return newAnimeLoadResponse(displayTitle, pageUrl, TvType.Anime) {
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = plotText
            year = releaseYear
            score = parsedScore
            tags = genres.distinct().takeIf { it.isNotEmpty() }
            showStatus = resolvedStatus
            trailerUrl?.let { addTrailer(listOf(it)) }
            addEpisodes(dubStatus, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawLinks = decodeLinkPayload(data)
        if (rawLinks.isEmpty()) return false

        // Prioritize HubCloud and GDFlix (verified direct Google CDN / R2 streams) first
        val sortedLinks = rawLinks.sortedBy { item ->
            val u = item.url.lowercase()
            val h = item.host.lowercase()
            when {
                u.contains("hubcloud") || h.contains("hubcloud") -> 0
                u.contains("gdflix") || h.contains("gdflix") -> 1
                u.contains("filepress") || h.contains("filepress") -> 2
                else -> 3
            }
        }

        var emitted = false
        val seenUrls = mutableSetOf<String>()

        for (item in sortedLinks) {
            val linkUrl = item.url.trim()
            if (linkUrl.isEmpty() || !seenUrls.add(linkUrl)) continue

            val qualityLabel = item.quality.ifBlank { "Auto" }
            val hostLower = item.host.lowercase()
            val urlLower = linkUrl.lowercase()

            when {
                urlLower.contains("hubcloud") || hostLower.contains("hubcloud") -> {
                    if (resolveHubCloud(linkUrl, qualityLabel, subtitleCallback, callback)) {
                        emitted = true
                    }
                }
                urlLower.contains("gdflix") || hostLower.contains("gdflix") -> {
                    if (resolveGdFlix(linkUrl, qualityLabel, subtitleCallback, callback)) {
                        emitted = true
                    }
                }
                urlLower.contains("t.me/") || urlLower.contains("telegram.me/") -> Unit
                else -> {
                    try {
                        if (loadExtractor(linkUrl, "$linkHubBase/", subtitleCallback, callback)) {
                            emitted = true
                        }
                    } catch (_: Exception) {
                        // Skip unsupported mirror host
                    }
                }
            }
        }
        return emitted
    }

    private suspend fun fetchLinkHubDocument(linkHubUrl: String, refererUrl: String): Document? {
        val directRes = try {
            app.get(
                linkHubUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to refererUrl
                )
            )
        } catch (_: Exception) {
            null
        }

        if (directRes != null && directRes.code in 200..299) {
            val html = directRes.text
            if (!html.contains("_cf_chl_opt") &&
                !html.contains("<title>Just a moment...</title>", ignoreCase = true) &&
                (html.contains("adc-wrap") || html.contains("adc-ep") || html.contains("adc-btn"))
            ) {
                return directRes.document
            }
        }

        var capturedHtml: String? = null
        val resolver = WebViewResolver(
            interceptUrl = Regex("^https://never-match-animedrive\\.invalid$"),
            additionalUrls = listOf(Regex(".*")),
            userAgent = null,
            useOkhttp = false,
            script = "(function(){var el=document.querySelector('.adc-wrap,.entry-content');return el?el.outerHTML:'';})()",
            scriptCallback = { rawJson ->
                val decoded = try {
                    mapper.readTree(rawJson).asText("")
                } catch (_: Exception) {
                    ""
                }
                if (decoded.contains("adc-ep") ||
                    decoded.contains("adc-btn") ||
                    decoded.contains("adc-wrap")
                ) {
                    capturedHtml = decoded
                }
            },
            timeout = 20_000L
        )

        try {
            resolver.resolveUsingWebView(
                url = linkHubUrl,
                referer = refererUrl
            ) {
                capturedHtml != null
            }
        } catch (_: Exception) {
            return null
        }

        val html = capturedHtml ?: return null
        return Jsoup.parseBodyFragment(html, linkHubUrl)
    }

    private fun parseSeriesEpisodes(doc: Document, origin: String): List<ParsedEpisode> {
        val episodes = doc.select("div.adc-ep").mapNotNull { card ->
            if (card.hasClass("adc-zip-card")) return@mapNotNull null

            val badgeText = card.selectFirst("span.adc-badge")?.text()?.trim().orEmpty()
            val epName = card.selectFirst("span.adc-epname")?.text()?.trim().orEmpty()
            if (epName.contains("ZIP", ignoreCase = true) &&
                badgeText.contains("ZIP", ignoreCase = true)
            ) {
                return@mapNotNull null
            }

            val epNum = Regex("(\\d+)").find(badgeText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("(?:Episode|Ep)\\s*0*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(epName)?.groupValues?.get(1)?.toIntOrNull()

            val links = extractLinksFromContainer(card, origin)
            if (links.isEmpty()) return@mapNotNull null

            val displayEpName = epName.ifBlank {
                if (epNum != null) "Episode $epNum" else badgeText.ifBlank { "Episode" }
            }
            ParsedEpisode(episodeNumber = epNum, title = displayEpName, links = links)
        }

        if (episodes.isNotEmpty()) return episodes

        // Fallback if a single-episode or movie-style card uses adc-hoster without adc-ep
        val fallbackLinks = parseMovieLinks(doc, origin)
        if (fallbackLinks.isEmpty()) return emptyList()
        return listOf(ParsedEpisode(episodeNumber = 1, title = "Episode 1", links = fallbackLinks))
    }

    private fun parseMovieLinks(doc: Document, origin: String): List<ParsedLink> {
        val nonZipWrap = doc.select("div.adc-ep").filterNot { it.hasClass("adc-zip-card") }
        if (nonZipWrap.isNotEmpty()) {
            val collected = mutableListOf<ParsedLink>()
            nonZipWrap.forEach { card ->
                collected.addAll(extractLinksFromContainer(card, origin))
            }
            if (collected.isNotEmpty()) return collected.distinctBy { it.url }
        }
        return extractLinksFromContainer(doc.selectFirst("div.adc-wrap") ?: doc.body(), origin)
            .distinctBy { it.url }
    }

    private fun extractLinksFromContainer(container: Element, origin: String): List<ParsedLink> {
        val collected = mutableListOf<ParsedLink>()
        val hosters = container.select("div.adc-hoster")

        if (hosters.isNotEmpty()) {
            hosters.forEach { hoster ->
                val hostLabel = hoster.selectFirst("div.adc-hoster-lbl")?.text()?.trim().orEmpty()
                hoster.select("a.adc-btn[href], a[data-adlr-link][href], div.adc-btns a[href]")
                    .forEach { anchor ->
                        parseAdcAnchor(anchor, hostLabel, origin)?.let { collected.add(it) }
                    }
            }
            return collected
        }

        container.select("a.adc-btn[href], a[data-adlr-link][href]").forEach { anchor ->
            parseAdcAnchor(anchor, "", origin)?.let { collected.add(it) }
        }
        return collected
    }

    private fun parseAdcAnchor(anchor: Element, hostLabel: String, origin: String): ParsedLink? {
        val rawHref = anchor.attr("href").trim()
        if (rawHref.isEmpty()) return null

        val resolvedHref = resolveRelativeUrl(rawHref, origin) ?: return null
        val targetUrl = decodeAdlrUrl(resolvedHref) ?: resolvedHref
        if (!targetUrl.startsWith("http", ignoreCase = true)) return null
        if (targetUrl.contains("t.me/", ignoreCase = true) ||
            targetUrl.contains("telegram.me/", ignoreCase = true)
        ) {
            return null
        }

        val btnText = anchor.text().trim()
        val qParam = extractQueryParam(resolvedHref, "q").orEmpty()
        val classQuality = when {
            anchor.hasClass("q1080") -> "1080p"
            anchor.hasClass("q720") -> "720p"
            anchor.hasClass("q480") -> "480p"
            anchor.hasClass("q360") -> "360p"
            else -> ""
        }
        val quality = extractQualityLabel("$btnText $qParam $classQuality")
        val resolvedHost = hostLabel.ifBlank { extractHostName(targetUrl) }

        return ParsedLink(
            quality = quality,
            host = resolvedHost,
            label = btnText,
            url = targetUrl
        )
    }

    private fun decodeAdlrUrl(rawUrl: String): String? {
        val path = try {
            URI(rawUrl).path.orEmpty()
        } catch (_: Exception) {
            rawUrl
        }
        if (!path.contains("/dl/")) return null
        val token = path.substringAfter("/dl/").substringBefore("/").substringBefore("?").trim()
        if (token.isEmpty()) return null

        val decoded = decodeBase64(token).reversed().trim()
        return decoded.takeIf {
            it.startsWith("http://", ignoreCase = true) ||
                it.startsWith("https://", ignoreCase = true)
        }
    }

    private suspend fun resolveHubCloud(
        driveUrl: String,
        qualityLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val step1Headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$linkHubBase/"
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
            ?: step1Doc.selectFirst("a#download[href]")?.attr("href")?.trim()?.ifBlank { null }
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
        var emitted = false
        val emittedStreams = mutableSetOf<String>()
        val anchors = step2Doc.select("a[href]")

        // 1. 10Gbps Server (pixel.hubcloud.* / gpdl.hubcloud.* -> video-downloads.googleusercontent.com)
        for (anchor in anchors) {
            val href = anchor.attr("href").trim()
            val text = anchor.text().trim()
            if (href.contains("pixel.hubcloud.", ignoreCase = true) ||
                href.contains("gpdl.hubcloud.", ignoreCase = true) ||
                text.contains("10Gbps", ignoreCase = true)
            ) {
                val resolvedGoogleUrl = resolveHubCloud10Gbps(href, hubPhpUrl)
                if (resolvedGoogleUrl != null && emittedStreams.add(resolvedGoogleUrl)) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name HubCloud 10Gbps [$qualityLabel]",
                            url = resolvedGoogleUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = parsedQuality
                            this.referer = ""
                        }
                    )
                    emitted = true
                }
            }
        }

        // 2. FSL Server (r2.cloudflarestorage.com) — probe 1 byte to ensure bucket link is live
        for (anchor in anchors) {
            val href = anchor.attr("href").trim()
            val text = anchor.text().trim()
            if (href.contains("r2.cloudflarestorage.com", ignoreCase = true) ||
                (text.contains("FSL", ignoreCase = true) && href.startsWith("http"))
            ) {
                if (emittedStreams.add(href) && probeRangeByte(href)) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name HubCloud FSL [$qualityLabel]",
                            url = href,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = parsedQuality
                            this.referer = ""
                        }
                    )
                    emitted = true
                }
            }
        }

        // 3. HBPlay decoded stream if distinct and live
        for (anchor in anchors) {
            val href = anchor.attr("href").trim()
            if (href.contains("hbplay.pages.dev", ignoreCase = true)) {
                val encodedStream = extractQueryParam(href, "u")
                val decodedStream = encodedStream?.let { decodeBase64(it) }?.trim()
                if (!decodedStream.isNullOrBlank() &&
                    decodedStream.startsWith("http") &&
                    emittedStreams.add(decodedStream) &&
                    probeRangeByte(decodedStream)
                ) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name HBPlay [$qualityLabel]",
                            url = decodedStream,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = parsedQuality
                            this.referer = ""
                        }
                    )
                    emitted = true
                }
            }
        }

        // 4. PixelDrain only if /api/file/<id>/info confirms file exists (skips dead placeholders like negn6f)
        for (anchor in anchors) {
            val href = anchor.attr("href").trim()
            if (href.contains("pixeldrain.", ignoreCase = true)) {
                val fileId = Regex("/u/([A-Za-z0-9]+)").find(href)?.groupValues?.get(1) ?: continue
                if (isPixelDrainAlive(fileId)) {
                    if (loadExtractor(href, hubPhpUrl, subtitleCallback, callback)) {
                        emitted = true
                    }
                }
            }
        }

        // 5. Buzz / other extractor-supported mirrors
        for (anchor in anchors) {
            val href = anchor.attr("href").trim()
            val text = anchor.text().trim()
            if (text.contains("Buzz Server", ignoreCase = true) ||
                href.contains("fuckingfast.", ignoreCase = true)
            ) {
                try {
                    if (loadExtractor(href, hubPhpUrl, subtitleCallback, callback)) {
                        emitted = true
                    }
                } catch (_: Exception) {
                    // Ignore unsupported buzz mirror
                }
            }
        }

        return emitted
    }

    private suspend fun resolveHubCloud10Gbps(startUrl: String, referer: String): String? {
        var currentUrl = startUrl
        var currentReferer = referer
        for (hop in 0 until 6) {
            val extractedLink = extractGoogleVideoUrl(currentUrl)
            if (extractedLink != null) return extractedLink

            val res = try {
                app.get(
                    currentUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to currentReferer
                    ),
                    allowRedirects = false
                )
            } catch (_: Exception) {
                return null
            }

            val location = (res.headers["Location"] ?: res.headers["location"])?.trim()
            if (!location.isNullOrBlank()) {
                val absLocation = resolveRelativeUrl(
                    location,
                    extractOrigin(currentUrl) ?: "https://hubcloud.mov"
                ) ?: return null
                val googleFromLoc = extractGoogleVideoUrl(absLocation)
                if (googleFromLoc != null) return googleFromLoc
                currentReferer = currentUrl
                currentUrl = absLocation
                continue
            }

            if (res.code in 200..299) {
                val ct = (res.headers["Content-Type"] ?: res.headers["content-type"]).orEmpty()
                if (ct.contains("video/", ignoreCase = true) ||
                    ct.contains("octet-stream", ignoreCase = true)
                ) {
                    return currentUrl
                }
                val bodyGoogleUrl = extractGoogleVideoUrl(res.text)
                if (bodyGoogleUrl != null) return bodyGoogleUrl
            }
            break
        }
        return null
    }

    private suspend fun resolveGdFlix(
        gdflixUrl: String,
        qualityLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$linkHubBase/"
        )
        val res = try {
            app.get(gdflixUrl, headers = headers)
        } catch (_: Exception) {
            return false
        }

        val doc = res.document
        val pageOrigin = extractOrigin(res.url) ?: extractOrigin(gdflixUrl) ?: "https://gdflix.dev"
        val parsedQuality = parseQualityFromText(qualityLabel)
        var emitted = false

        // 1. Instant DL (busycdn.instadl.rest -> fastdl-one.pages.dev/?url=video-downloads.googleusercontent.com/...)
        val busyUrl = doc.select("a[href]")
            .mapNotNull { resolveRelativeUrl(it.attr("href"), pageOrigin) }
            .firstOrNull {
                it.contains("busycdn.", ignoreCase = true) ||
                    it.contains("instadl.", ignoreCase = true)
            }

        if (busyUrl != null) {
            val fastDlRes = try {
                app.get(
                    busyUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to res.url
                    )
                )
            } catch (_: Exception) {
                null
            }
            val finalUrl = fastDlRes?.url.orEmpty()
            val directVideoUrl = extractQueryParam(finalUrl, "url")
                ?: extractGoogleVideoUrl(finalUrl)
                ?: fastDlRes?.text?.let { extractGoogleVideoUrl(it) }

            if (!directVideoUrl.isNullOrBlank() &&
                directVideoUrl.startsWith("http") &&
                probeRangeByte(directVideoUrl)
            ) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name GDFlix Instant [$qualityLabel]",
                        url = directVideoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = parsedQuality
                        this.referer = ""
                    }
                )
                emitted = true
            }
        }

        if (!emitted) {
            val fallbackMirrors = doc.select("a[href]")
                .mapNotNull { resolveRelativeUrl(it.attr("href"), pageOrigin) }
                .filter {
                    it.contains("goflix.", ignoreCase = true) ||
                        it.contains("drivebot.", ignoreCase = true)
                }
                .distinct()
                .take(2)
            for (mirror in fallbackMirrors) {
                try {
                    if (loadExtractor(mirror, res.url, subtitleCallback, callback)) {
                        emitted = true
                    }
                } catch (_: Exception) {
                    // Skip unsupported GDFlix mirror
                }
            }
        }

        return emitted
    }

    private fun extractGoogleVideoUrl(text: String): String? {
        val linkParam = extractQueryParam(text, "link")
        if (!linkParam.isNullOrBlank() &&
            linkParam.contains("video-downloads.googleusercontent.com", ignoreCase = true)
        ) {
            return linkParam.trim()
        }
        val urlParam = extractQueryParam(text, "url")
        if (!urlParam.isNullOrBlank() &&
            urlParam.contains("video-downloads.googleusercontent.com", ignoreCase = true)
        ) {
            return urlParam.trim()
        }
        val regexMatch = Regex("""https://video-downloads\.googleusercontent\.com/[^\s"'<>]+""")
            .find(text)?.value?.trim()
        return regexMatch?.replace("&amp;", "&")
    }

    private suspend fun isPixelDrainAlive(fileId: String): Boolean {
        return try {
            val res = app.get(
                "https://pixeldrain.dev/api/file/$fileId/info",
                headers = mapOf("User-Agent" to USER_AGENT)
            )
            res.code == 200 && res.text.contains("\"success\":true")
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun probeRangeByte(url: String): Boolean {
        return try {
            val res = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Range" to "bytes=0-0"
                ),
                allowRedirects = true
            )
            val ct = (res.headers["Content-Type"] ?: res.headers["content-type"]).orEmpty()
            res.code in 200..299 && !ct.contains("text/html", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
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
            val isMovie = article.hasClass("category-movies") ||
                rawTitle.contains("Full Movie", ignoreCase = true) ||
                Regex("\\bMovie\\b", RegexOption.IGNORE_CASE).containsMatchIn(rawTitle)
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
        if (doc.selectFirst("nav.navigation.pagination a.next.page-numbers, a.next.page-numbers") != null) {
            return true
        }
        return doc.select("nav.navigation.pagination a[href], div.nav-links a[href]").any { a ->
            val pageNum = Regex("/page/(\\d+)/?")
                .find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            pageNum != null && pageNum > currentPage
        }
    }

    private fun extractPlot(contentEl: Element): String? {
        val storyHeading = contentEl.select("h2, h3, h4").firstOrNull {
            val t = it.text()
            t.contains("Detailed Story", ignoreCase = true) ||
                t.contains("Story & Plot", ignoreCase = true)
        }

        if (storyHeading != null) {
            val parts = mutableListOf<String>()
            var sibling = storyHeading.nextElementSibling()
            while (sibling != null) {
                val tag = sibling.tagName().lowercase()
                if (tag in setOf("h2", "h3", "h4", "div")) break
                if (tag == "p") {
                    val txt = sibling.text().replace('\u00A0', ' ').trim()
                    if (txt.isNotEmpty()) parts.add(txt)
                }
                sibling = sibling.nextElementSibling()
            }
            if (parts.isNotEmpty()) {
                return parts.joinToString("\n\n")
            }
        }

        val introParagraphs = contentEl.select("> p")
            .map { it.text().replace('\u00A0', ' ').trim() }
            .filter {
                it.length > 40 &&
                    !it.contains("Join Telegram", ignoreCase = true) &&
                    !it.contains("Screenshots", ignoreCase = true)
            }
        return introParagraphs.take(2).takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    private fun extractTrailerUrl(contentEl: Element): String? {
        val iframeSrc = contentEl.selectFirst("div.anime-trailer-section iframe[src], iframe[src*='youtube']")
            ?.attr("src")?.trim()?.ifBlank { null }
        val rawUrl = iframeSrc ?: contentEl.selectFirst("a[href*='youtu.be'], a[href*='youtube.com/watch']")
            ?.attr("href")?.trim()?.ifBlank { null }
        ?: return null

        val embedMatch = Regex("youtube(?:-nocookie)?\\.com/embed/([A-Za-z0-9_-]+)").find(rawUrl)
        if (embedMatch != null) {
            return "https://www.youtube.com/watch?v=${embedMatch.groupValues[1]}"
        }
        val shortMatch = Regex("youtu\\.be/([A-Za-z0-9_-]+)").find(rawUrl)
        if (shortMatch != null) {
            return "https://www.youtube.com/watch?v=${shortMatch.groupValues[1]}"
        }
        return rawUrl
    }

    private fun encodeLinkPayload(links: List<ParsedLink>): String {
        val array = mapper.createArrayNode()
        links.forEach { link ->
            val obj = mapper.createObjectNode()
            obj.put("q", link.quality)
            obj.put("h", link.host)
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
                        host = node.path("h").asText(""),
                        label = node.path("l").asText(""),
                        url = url
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        if (trimmed.startsWith("http")) {
            return listOf(ParsedLink(quality = "", host = "", label = "", url = trimmed))
        }
        return emptyList()
    }

    private fun cleanPostTitle(rawTitle: String): String {
        var cleaned = rawTitle.replace('\u00A0', ' ').trim()
        cleaned = cleaned.replace(
            Regex("\\s*(?:\\[|\\()?(?:Hindi|Dual\\s*Audio|Multi\\s*Audio)[^|\\n]*$", RegexOption.IGNORE_CASE),
            ""
        )
        cleaned = cleaned.replace(
            Regex("\\s*(?:480p|720p|1080p|WEB-DL|BluRay|10bit|HEVC|Hindi\\s+Download|Episodes\\s+Download).*$", RegexOption.IGNORE_CASE),
            ""
        )
        cleaned = cleaned.trim(' ', '-', '–', '|', ':')
        return cleaned.ifBlank { rawTitle.trim() }
    }

    private fun cleanInfoValue(raw: String): String {
        return raw.replace('\u00A0', ' ')
            .replace("Ã‚", "")
            .trim()
    }

    private fun extractSeasonNumber(text: String): Int {
        val direct = text.trim().toIntOrNull()
        if (direct != null) return direct
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

    private fun extractQualityLabel(text: String): String {
        val res = Regex("\\b(360|480|720|1080|2160)p\\b", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
        if (res != null) return "${res}p"
        val qCode = Regex("\\bq(360|480|720|1080)\\b", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
        return if (qCode != null) "${qCode}p" else ""
    }

    private fun parseQualityFromText(qualityText: String): Int {
        val resMatch = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE).find(qualityText)
        return if (resMatch != null) {
            getQualityFromName("${resMatch.groupValues[1]}p")
        } else {
            getQualityFromName(qualityText)
        }
    }

    private fun extractHostName(url: String): String {
        return try {
            URI(url).host.orEmpty().removePrefix("www.")
        } catch (_: Exception) {
            ""
        }
    }

    private fun resolveRelativeUrl(rawHref: String, origin: String): String? {
        val trimmed = rawHref.trim()
        if (trimmed.isEmpty() ||
            trimmed.startsWith("#") ||
            trimmed.startsWith("javascript:", ignoreCase = true)
        ) {
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
