package com.errorrepo

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
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

class HindiAnimeDubProvider : MainAPI() {
    override var mainUrl = "https://hindidubanime.com"
    override var name = "Hindi Anime Dub"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val mapper = jacksonObjectMapper()
    private val defaultHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    private val blakiteApiBase = "https://blakiteapi.xyz"
    private val blakiteCdnBase = "https://hugh.cdn.rumble.cloud/video/"

    private val subRegex = Regex("\\b(sub|subbed)\\b", RegexOption.IGNORE_CASE)
    private val dubRegex = Regex("\\b(dub|dubbed)\\b", RegexOption.IGNORE_CASE)

    override val mainPage = mainPageOf(
        "updated" to "Recently Updated",
        "popular" to "Most Popular",
        "type/tv" to "Anime Series",
        "type/movie" to "Anime Movies",
        "status/airing" to "Ongoing / Airing",
        "status/completed" to "Completed Series",
        "type/ova" to "OVA",
        "type/ona" to "ONA"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val currentPage = page.coerceAtLeast(1)
        return when (request.data) {
            "updated" -> fetchAdvancedSearchPage(
                page = currentPage,
                title = request.name,
                singleParams = mapOf(
                    "paged" to currentPage,
                    "orderby" to "meta_value_num",
                    "meta_key" to "kiranime_anime_updated"
                )
            )
            "popular" -> fetchAdvancedSearchPage(
                page = currentPage,
                title = request.name,
                singleParams = mapOf(
                    "paged" to currentPage,
                    "orderby" to "meta_value_num",
                    "meta_key" to "total_kiranime_views"
                )
            )
            else -> {
                val archivePath = when {
                    request.data.startsWith("type/") -> "anime-type/${request.data.removePrefix("type/")}"
                    request.data.startsWith("status/") -> "status/${request.data.removePrefix("status/")}"
                    else -> request.data
                }
                val url = if (currentPage <= 1) {
                    "$mainUrl/$archivePath/"
                } else {
                    "$mainUrl/$archivePath/page/$currentPage/"
                }
                val doc = try {
                    app.get(url, headers = defaultHeaders).document
                } catch (_: Exception) {
                    return newHomePageResponse(emptyList(), false)
                }
                val items = parseArchiveCards(doc)
                val hasNext = hasNextArchivePage(doc, currentPage)
                newHomePageResponse(
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
        }
    }

    private suspend fun fetchAdvancedSearchPage(
        page: Int,
        title: String,
        singleParams: Map<String, Any>
    ): HomePageResponse {
        val payload = mapOf(
            "keyword" to "",
            "query" to "",
            "single" to singleParams,
            "tax" to emptyList<Any>()
        )
        val responseText = try {
            app.post(
                "$mainUrl/wp-json/kiranime/v1/anime/advancedsearch",
                headers = defaultHeaders,
                json = payload
            ).text
        } catch (_: Exception) {
            return newHomePageResponse(emptyList(), false)
        }
        val root = try {
            mapper.readTree(responseText)
        } catch (_: Exception) {
            return newHomePageResponse(emptyList(), false)
        }
        val htmlData = root.path("data").asText("")
        if (htmlData.isBlank()) {
            return newHomePageResponse(emptyList(), false)
        }
        val totalPages = root.path("pages").asInt(1)
        val doc = Jsoup.parseBodyFragment(htmlData, mainUrl)
        val items = parseArchiveCards(doc)
        return newHomePageResponse(
            listOf(
                HomePageList(
                    name = title,
                    list = items,
                    isHorizontalImages = false
                )
            ),
            hasNext = page < totalPages && items.isNotEmpty()
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val clean = query.trim()
        if (clean.isEmpty()) return emptyList()
        val encoded = try {
            URLEncoder.encode(clean, "UTF-8")
        } catch (_: Exception) {
            clean
        }
        val responseText = try {
            app.get(
                "$mainUrl/wp-json/kiranime/v1/anime/search?query=$encoded",
                headers = defaultHeaders
            ).text
        } catch (_: Exception) {
            return emptyList()
        }
        val root = try {
            mapper.readTree(responseText)
        } catch (_: Exception) {
            return emptyList()
        }
        val htmlResult = root.path("result").asText("")
        if (htmlResult.isBlank()) return emptyList()
        val doc = Jsoup.parseBodyFragment(htmlResult, mainUrl)
        return doc.select("a[href*='/anime/']").mapNotNull { linkEl ->
            val href = linkEl.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null
            val itemUrl = fixUrl(href)
            val title = linkEl.selectFirst("span[data-nt-title]")?.text()?.trim()?.ifBlank { null }
                ?: linkEl.selectFirst("span[data-en-title]")?.text()?.trim()?.ifBlank { null }
                ?: linkEl.selectFirst("h3")?.text()?.trim()?.ifBlank { null }
                ?: linkEl.selectFirst("img")?.attr("alt")?.removeSuffix(" thumbnail")?.trim()
                ?: return@mapNotNull null
            val poster = linkEl.selectFirst("img").extractImageUrl()
            val typeBadge = linkEl.selectFirst("span.uppercase")?.text()?.trim().orEmpty()
            buildSearchResponse(
                title = title,
                url = itemUrl,
                poster = poster,
                typeLabel = typeBadge,
                audioBadge = title,
                episodeCount = null
            )
        }.distinctBy { it.url }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val clean = query.trim()
        if (clean.isEmpty()) return newSearchResponseList(emptyList(), false)
        val currentPage = page.coerceAtLeast(1)
        val payload = mapOf(
            "keyword" to clean,
            "query" to clean,
            "single" to mapOf("paged" to currentPage),
            "tax" to emptyList<Any>()
        )
        val responseText = try {
            app.post(
                "$mainUrl/wp-json/kiranime/v1/anime/advancedsearch",
                headers = defaultHeaders,
                json = payload
            ).text
        } catch (_: Exception) {
            return newSearchResponseList(emptyList(), false)
        }
        val root = try {
            mapper.readTree(responseText)
        } catch (_: Exception) {
            return newSearchResponseList(emptyList(), false)
        }
        val htmlData = root.path("data").asText("")
        if (htmlData.isBlank()) {
            return newSearchResponseList(emptyList(), false)
        }
        val totalPages = root.path("pages").asInt(1)
        val doc = Jsoup.parseBodyFragment(htmlData, mainUrl)
        val items = parseArchiveCards(doc)
        return newSearchResponseList(items, hasNext = currentPage < totalPages && items.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = try {
            app.get(pageUrl, headers = defaultHeaders).document
        } catch (_: Exception) {
            return null
        }

        val title = doc.selectFirst("h1 span[data-nt-title]")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("h1 span[data-en-title]")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: return null

        val poster = doc.selectFirst("div.anime-image img").extractImageUrl()
        val backdrop = doc.selectFirst("div.background-image img").extractImageUrl() ?: poster
        val synopsis = doc.selectFirst("div[data-synopsis]")?.text()?.trim()?.ifBlank { null }

        var releaseYear: Int? = null
        var ratingText: String? = null
        var statusText = ""
        val genres = mutableListOf<String>()

        doc.select("section ul li").forEach { li ->
            val label = li.selectFirst("span.font-semibold")?.text()?.trim().orEmpty()
            if (label.isEmpty()) return@forEach
            val valueText = li.text().removePrefix(label).trim()
            when {
                label.startsWith("Aired", ignoreCase = true) ||
                    label.startsWith("Premiered", ignoreCase = true) -> {
                    if (releaseYear == null) {
                        releaseYear = Regex("\\b(19|20)\\d{2}\\b")
                            .find(valueText)?.value?.toIntOrNull()
                    }
                }
                label.startsWith("Score", ignoreCase = true) -> {
                    ratingText = valueText.takeIf { it.isNotBlank() }
                }
                label.startsWith("Status", ignoreCase = true) -> {
                    statusText = valueText
                }
                label.startsWith("Genre", ignoreCase = true) -> {
                    li.select("a").forEach { a ->
                        a.text().trim().takeIf { it.isNotEmpty() }?.let { genres.add(it) }
                    }
                }
            }
        }

        val parsedScore = Score.from10(ratingText)
        val resolvedStatus = when {
            statusText.contains("Completed", ignoreCase = true) -> ShowStatus.Completed
            statusText.contains("Airing", ignoreCase = true) ||
                statusText.contains("Ongoing", ignoreCase = true) -> ShowStatus.Ongoing
            else -> null
        }

        val metaType = doc.select("ul.anime-metadata li a[href*='/anime-type/'], ul.anime-metadata li span")
            .map { it.text().trim().uppercase() }
            .firstOrNull { it in setOf("TV", "MOVIE", "OVA", "ONA", "SPECIAL") }
            ?: doc.selectFirst("nav[aria-label='Breadcrumb'] a[href*='/anime-type/']")
                ?.text()?.trim()?.uppercase().orEmpty()

        val audioMeta = doc.select("ul.anime-metadata li span")
            .joinToString(" ") { it.text().trim() }
        val dubStatus = resolveDubStatus(audioMeta, title)

        val episodeElements = doc.select("div.swiper-episode-anime div.swiper-slide a[href*='/watch/']")
        val watchNowHref = doc.selectFirst("a[href*='/watch/']:contains(Watch Now)")
            ?.attr("href")?.trim()?.takeIf { it.isNotBlank() }

        val recommendations = doc.select("ul.bg-overlay li").mapNotNull { li ->
            val linkEl = li.selectFirst("h3 a[href*='/anime/']") ?: return@mapNotNull null
            val recUrl = fixUrl(linkEl.attr("href"))
            val recTitle = linkEl.selectFirst("span[data-nt-title]")?.text()?.trim()?.ifBlank { null }
                ?: linkEl.selectFirst("span[data-en-title]")?.text()?.trim()?.ifBlank { null }
                ?: linkEl.attr("title").trim().ifBlank { null }
                ?: linkEl.text().trim()
            if (recTitle.isBlank()) return@mapNotNull null
            val recPoster = li.selectFirst("img").extractImageUrl()
            val recType = li.selectFirst("div.text-spec span.uppercase")?.text()?.trim().orEmpty()
            val recEpCount = Regex("E\\s*(\\d+)")
                .find(li.selectFirst("div.text-spec")?.text().orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()
            buildSearchResponse(
                title = recTitle,
                url = recUrl,
                poster = recPoster,
                typeLabel = recType,
                audioBadge = recTitle,
                episodeCount = recEpCount
            )
        }.distinctBy { it.url }

        if (metaType == "MOVIE") {
            val movieWatchUrl = fixUrl(
                watchNowHref
                    ?: episodeElements.firstOrNull()?.attr("href")
                    ?: findWatchLinksFromHomepage(pageUrl).firstOrNull()
                    ?: return null
            )
            return newMovieLoadResponse(title, pageUrl, TvType.AnimeMovie, movieWatchUrl) {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                plot = synopsis
                year = releaseYear
                score = parsedScore
                tags = genres.distinct().takeIf { it.isNotEmpty() }
                this.recommendations = recommendations
            }
        }

        // Site lists episodes newest-first in the slider; reverse to restore ascending order
        val episodes = episodeElements.reversed().mapIndexedNotNull { index, el ->
            val epHref = el.attr("href").trim()
            if (epHref.isBlank()) return@mapIndexedNotNull null
            val epUrl = fixUrl(epHref)
            val epLabel = el.selectFirst("span.w-percentile")?.text()?.trim()?.ifBlank { null }
                ?: el.attr("title").trim().ifBlank { null }
                ?: "Episode ${index + 1}"
            val epNumber = Regex("(?:Episode|Ep)\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(epLabel)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("-episode-(\\d+)/?$", RegexOption.IGNORE_CASE)
                    .find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: (index + 1)
            val epThumb = el.selectFirst("img").extractImageUrl() ?: backdrop ?: poster
            newEpisode(epUrl) {
                this.name = epLabel
                this.season = 1
                this.episode = epNumber
                this.posterUrl = epThumb
            }
        }.ifEmpty {
            val fallbackWatchUrls = watchNowHref?.let { listOf(fixUrl(it)) }
                ?: findWatchLinksFromHomepage(pageUrl)
            fallbackWatchUrls.mapIndexed { idx, epUrl ->
                val epNum = Regex("-episode-(\\d+)/?$", RegexOption.IGNORE_CASE)
                    .find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                newEpisode(epUrl) {
                    this.name = "Episode $epNum"
                    this.season = 1
                    this.episode = epNum
                    this.posterUrl = backdrop ?: poster
                }
            }
        }

        val tvType = when (metaType) {
            "OVA", "ONA" -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(title, pageUrl, tvType) {
            posterUrl = poster
            backgroundPosterUrl = backdrop
            plot = synopsis
            year = releaseYear
            score = parsedScore
            tags = genres.distinct().takeIf { it.isNotEmpty() }
            showStatus = resolvedStatus
            this.recommendations = recommendations
            addEpisodes(dubStatus, episodes)
        }
    }

    private suspend fun findWatchLinksFromHomepage(animeUrl: String): List<String> {
        val slug = animeUrl.trimEnd('/').substringAfterLast('/').trim()
        if (slug.isBlank()) return emptyList()
        val homeDoc = try {
            app.get("$mainUrl/homepage/", headers = defaultHeaders).document
        } catch (_: Exception) {
            return emptyList()
        }
        return homeDoc.select("a[href*='/watch/']")
            .map { fixUrl(it.attr("href").trim()) }
            .filter { it.contains("/watch/$slug", ignoreCase = true) }
            .distinct()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = fixUrl(data)
        val doc = try {
            app.get(watchUrl, headers = defaultHeaders).document
        } catch (_: Exception) {
            return false
        }

        var foundAny = false
        val seenEmbedUrls = mutableSetOf<String>()
        val embedElements = doc.select("[data-embed-id]")

        for (el in embedElements) {
            val rawEmbedId = el.attr("data-embed-id").trim()
            if (!rawEmbedId.contains(":")) continue
            val parts = rawEmbedId.split(":", limit = 2)
            val serverLabel = decodeBase64(parts[0]).ifBlank { el.text().trim() }
            val embedUrl = normalizeEmbedUrl(decodeBase64(parts[1])) ?: continue
            if (!seenEmbedUrls.add(embedUrl)) continue

            val categoryTag = when {
                el.parents().any { it.hasClass("player-sub") } -> "SUB"
                el.parents().any { it.hasClass("player-dub") } -> "DUB"
                else -> ""
            }
            val displayLabel = listOf(serverLabel, categoryTag)
                .filter { it.isNotBlank() }
                .joinToString(" ")

            if (extractEmbedUrl(embedUrl, displayLabel, watchUrl, subtitleCallback, callback, depth = 0)) {
                foundAny = true
            }
        }

        // Also inspect any direct/lazy iframe rendered on the watch page
        doc.select("iframe[data-litespeed-src], iframe[data-src], iframe[src]").forEach { iframe ->
            val rawIframeUrl = iframe.attr("data-litespeed-src").trim()
                .ifBlank { iframe.attr("data-src").trim() }
                .ifBlank { iframe.attr("src").trim() }
            val iframeUrl = normalizeEmbedUrl(rawIframeUrl) ?: return@forEach
            if (seenEmbedUrls.add(iframeUrl)) {
                if (extractEmbedUrl(iframeUrl, name, watchUrl, subtitleCallback, callback, depth = 0)) {
                    foundAny = true
                }
            }
        }

        return foundAny
    }

    private suspend fun extractEmbedUrl(
        embedUrl: String,
        serverLabel: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ): Boolean {
        if (depth > 2) return false
        val lower = embedUrl.lowercase()
        return when {
            lower.contains("blakiteapi.xyz/embed/") -> {
                extractBlakiteEmbed(embedUrl, serverLabel, callback)
            }
            lower.contains("vidara.to/") || lower.contains("vid.dubanime.me/") -> {
                extractVidaraEmbed(embedUrl, serverLabel, subtitleCallback, callback)
            }
            lower.contains("ravok.buzz/video/") ||
                Regex("https?://[^/]+/video/[a-z0-9]{16,}").containsMatchIn(lower) -> {
                extractVPlayerEmbed(embedUrl, serverLabel, callback)
            }
            lower.contains("nuvorel.top/") ||
                lower.contains("morencius.com/") ||
                lower.contains("vidmoly.net/") ||
                lower.contains("vidmoly.biz/") -> {
                extractDirectOrPackedEmbed(embedUrl, serverLabel, refererUrl, subtitleCallback, callback)
            }
            lower.contains("player.php") && lower.contains("data=") -> {
                extractBase64PlaylistEmbed(embedUrl, serverLabel, subtitleCallback, callback, depth)
            }
            else -> {
                val builtInOk = try {
                    loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
                } catch (_: Exception) {
                    false
                }
                if (builtInOk) return true
                extractGenericOrNestedEmbed(
                    embedUrl,
                    serverLabel,
                    refererUrl,
                    subtitleCallback,
                    callback,
                    depth
                )
            }
        }
    }

    private suspend fun extractBase64PlaylistEmbed(
        embedUrl: String,
        serverLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ): Boolean {
        val rawParam = try {
            val query = URI(embedUrl).rawQuery ?: return false
            query.split("&")
                .firstOrNull { it.startsWith("data=") }
                ?.substringAfter("data=")
                ?.let { URLDecoder.decode(it, "UTF-8") }
                ?: return false
        } catch (_: Exception) {
            return false
        }
        val decodedJson = decodeBase64(rawParam)
        if (decodedJson.isBlank()) return false
        val arrayNode = try {
            mapper.readTree(decodedJson)
        } catch (_: Exception) {
            return false
        }
        if (!arrayNode.isArray) return false
        var found = false
        arrayNode.forEach { item ->
            val link = normalizeEmbedUrl(item.path("link").asText("")) ?: return@forEach
            val lang = item.path("language").asText("").trim()
            val label = if (lang.isNotBlank()) "$serverLabel ($lang)" else serverLabel
            if (extractEmbedUrl(link, label, embedUrl, subtitleCallback, callback, depth + 1)) {
                found = true
            }
        }
        return found
    }

    private suspend fun extractVidaraEmbed(
        embedUrl: String,
        serverLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = extractOrigin(embedUrl) ?: return false
        val fileCode = Regex("/(?:e|v)/([a-zA-Z0-9_-]+)")
            .find(embedUrl)?.groupValues?.get(1) ?: return false
        val apiUrl = "$origin/api/stream"
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$origin/e/$fileCode",
            "Origin" to origin
        )
        val responseText = try {
            app.post(
                apiUrl,
                headers = headers,
                json = mapOf(
                    "filecode" to fileCode,
                    "device" to "desktop"
                )
            ).text
        } catch (_: Exception) {
            return false
        }
        val root = try {
            mapper.readTree(responseText)
        } catch (_: Exception) {
            return false
        }
        val streamingUrl = root.path("streaming_url").asText("").trim()
        if (!streamingUrl.startsWith("http")) return false

        val labelPrefix = if (serverLabel.isNotBlank()) serverLabel else "Vidara"
        callback(
            newExtractorLink(
                source = name,
                name = labelPrefix,
                url = streamingUrl,
                type = if (streamingUrl.contains(".m3u8", ignoreCase = true)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
            ) {
                this.referer = "$origin/"
            }
        )

        root.path("subtitles").forEach { sub ->
            val subUrl = sub.path("file_path").asText("").trim()
            val subLang = sub.path("language").asText("English").trim()
            if (subUrl.startsWith("http")) {
                subtitleCallback(
                    newSubtitleFile(
                        lang = subLang.ifBlank { "English" },
                        url = subUrl
                    )
                )
            }
        }
        return true
    }

    private suspend fun extractBlakiteEmbed(
        embedUrl: String,
        serverLabel: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val afterEmbed = embedUrl.substringAfter("/embed/", "")
            .substringBefore("?")
            .trim('/')
        if (afterEmbed.isBlank()) return false
        val segments = afterEmbed.split("/").filter { it.isNotBlank() }
        val tmdbId = segments.getOrNull(0) ?: return false
        val episodeKey = segments.getOrNull(1)
        val embedOrigin = extractOrigin(embedUrl) ?: blakiteApiBase

        // blakiteapi.xyz enforces same-origin Referer matching the API host (e.g. test.blakiteapi.xyz vs blakiteapi.xyz)
        val candidates = buildList {
            add(Triple(embedOrigin, embedUrl, embedOrigin))
            if (!embedOrigin.equals(blakiteApiBase, ignoreCase = true)) {
                add(Triple(blakiteApiBase, "$blakiteApiBase/embed/$afterEmbed", blakiteApiBase))
            }
        }

        var resolvedOrigin = embedOrigin
        var streamData: com.fasterxml.jackson.databind.JsonNode? = null
        for ((apiHost, refUrl, originHeader) in candidates) {
            val apiUrl = if (episodeKey.isNullOrBlank()) {
                "$apiHost/api/get.php?tmdbId=$tmdbId"
            } else {
                "$apiHost/api/get.php?id=$episodeKey&tmdbId=$tmdbId"
            }
            val streamHeaders = mapOf(
                "Referer" to refUrl,
                "Origin" to originHeader,
                "User-Agent" to USER_AGENT
            )
            val responseText = try {
                app.get(apiUrl, headers = streamHeaders).text
            } catch (_: Exception) {
                continue
            }
            val root = try {
                mapper.readTree(responseText)
            } catch (_: Exception) {
                continue
            }
            val dataNode = root.path("data")
            if (!dataNode.isMissingNode && dataNode.isObject) {
                resolvedOrigin = originHeader
                streamData = dataNode
                break
            }
        }

        val data = streamData ?: return false
        val directVideoUrl = data.path("videoUrl").asText("").trim()
        val langLabel = data.path("language").asText("").trim()
        val labelPrefix = if (serverLabel.isNotBlank()) serverLabel else name
        val langSuffix = if (langLabel.isNotBlank()) " ($langLabel)" else ""

        if (directVideoUrl.isNotEmpty()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "$labelPrefix$langSuffix",
                    url = directVideoUrl,
                    type = if (directVideoUrl.contains(".m3u8", ignoreCase = true)) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                ) {
                    this.referer = "$resolvedOrigin/"
                }
            )
            return true
        }

        val dataId = data.path("dataId").asText("").trim()
        if (dataId.isEmpty()) return false
        val format = data.path("format").asText("M3U8").trim()
        val qid = data.path("qid").asInt(5).coerceIn(1, 5)
        val rangesRaw = data.path("ranges").asText("")
        val qualityCodes = listOf("oaa", "baa", "caa", "gaa", "haa")
        val qualityLabels = listOf("240p", "360p", "480p", "720p", "1080p")
        var emitted = false

        if (format.equals("M3U8", ignoreCase = true) && rangesRaw.isNotBlank()) {
            val rangeMap = mutableMapOf<String, String>()
            val lineRegex = Regex("^(\\d+-\\d+)\\s*\\(([^)]+)\\)")
            rangesRaw.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { line ->
                    val match = lineRegex.find(line)
                    if (match != null) {
                        rangeMap[match.groupValues[2].trim()] = match.groupValues[1]
                    }
                }
            for (i in qualityLabels.indices.reversed()) {
                val label = qualityLabels[i]
                val range = rangeMap[label] ?: continue
                val code = qualityCodes[i]
                val m3u8Url =
                    "$blakiteCdnBase$dataId.$code.tar?r_file=chunklist.m3u8&r_type=application%2Fvnd.apple.mpegurl&r_range=$range"
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$labelPrefix $label$langSuffix",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = getQualityFromName(label)
                        this.referer = "$resolvedOrigin/"
                    }
                )
                emitted = true
            }
        }

        if (!emitted) {
            for (i in (qid - 1) downTo 0) {
                val label = qualityLabels[i]
                val code = qualityCodes[i]
                val mp4Url = "$blakiteCdnBase$dataId.$code.mp4"
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$labelPrefix $label$langSuffix",
                        url = mp4Url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = getQualityFromName(label)
                        this.referer = "$resolvedOrigin/"
                    }
                )
                emitted = true
            }
        }
        return emitted
    }

    private suspend fun extractVPlayerEmbed(
        embedUrl: String,
        serverLabel: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = extractOrigin(embedUrl) ?: return false
        val videoId = Regex("/video/([a-zA-Z0-9]+)")
            .find(embedUrl)?.groupValues?.get(1) ?: return false
        val apiUrl = "$origin/player/index.php?data=$videoId&do=getVideo"
        val postHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to embedUrl,
            "Origin" to origin,
            "X-Requested-With" to "XMLHttpRequest"
        )
        val responseText = try {
            app.post(
                apiUrl,
                headers = postHeaders,
                data = mapOf(
                    "hash" to videoId,
                    "r" to "$mainUrl/"
                )
            ).text
        } catch (_: Exception) {
            return false
        }
        val root = try {
            mapper.readTree(responseText)
        } catch (_: Exception) {
            return false
        }

        val labelPrefix = if (serverLabel.isNotBlank()) serverLabel else "VPlayer"
        var emitted = false

        val streamUrl = root.path("securedLink").asText("").trim()
            .ifBlank { root.path("videoSource").asText("").trim() }
        if (streamUrl.startsWith("http")) {
            val isHls = root.path("hls").asBoolean(false) ||
                streamUrl.contains(".m3u8", ignoreCase = true)
            callback(
                newExtractorLink(
                    source = name,
                    name = labelPrefix,
                    url = streamUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$origin/"
                }
            )
            emitted = true
        }

        val sourcesArray = root.path("videoSources")
        if (!emitted && sourcesArray.isArray) {
            sourcesArray.forEach { srcNode ->
                val fileUrl = srcNode.path("file").asText("").trim()
                if (fileUrl.startsWith("http")) {
                    val qualityLabel = srcNode.path("label").asText("").trim()
                    val linkTitle = listOf(labelPrefix, qualityLabel)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    callback(
                        newExtractorLink(
                            source = name,
                            name = linkTitle,
                            url = fileUrl,
                            type = if (fileUrl.contains(".m3u8", ignoreCase = true)) {
                                ExtractorLinkType.M3U8
                            } else {
                                ExtractorLinkType.VIDEO
                            }
                        ) {
                            this.quality = getQualityFromName(qualityLabel)
                            this.referer = "$origin/"
                        }
                    )
                    emitted = true
                }
            }
        }

        return emitted
    }

    private suspend fun extractDirectOrPackedEmbed(
        embedUrl: String,
        serverLabel: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = extractOrigin(embedUrl) ?: "https://nuvorel.top"
        val html = try {
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

        if (extractMediaFromHtmlOrPacked(html, origin, serverLabel, subtitleCallback, callback)) {
            return true
        }

        return try {
            loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractGenericOrNestedEmbed(
        embedUrl: String,
        serverLabel: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ): Boolean {
        val html = try {
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

        val origin = extractOrigin(embedUrl) ?: "$mainUrl"

        // Handle Vidara-compatible pages on unknown domains if they call /api/stream
        if (html.contains("/api/stream") && Regex("/(?:e|v)/([a-zA-Z0-9_-]+)").containsMatchIn(embedUrl)) {
            if (extractVidaraEmbed(embedUrl, serverLabel, subtitleCallback, callback)) {
                return true
            }
        }

        // Extract direct or packed JWPlayer/HLS file URLs if present on unknown hosts
        if (extractMediaFromHtmlOrPacked(html, origin, serverLabel, subtitleCallback, callback)) {
            return true
        }

        // Unwrap nested wrapper iframes (e.g., hentaidekho.com/?trembed=...)
        val doc = Jsoup.parse(html, embedUrl)
        var found = false
        doc.select("iframe[data-litespeed-src], iframe[data-src], iframe[src]").forEach { iframe ->
            val rawNested = iframe.attr("data-litespeed-src").trim()
                .ifBlank { iframe.attr("data-src").trim() }
                .ifBlank { iframe.attr("src").trim() }
            val nestedUrl = normalizeEmbedUrl(rawNested) ?: return@forEach
            if (nestedUrl != embedUrl) {
                if (extractEmbedUrl(nestedUrl, serverLabel, embedUrl, subtitleCallback, callback, depth + 1)) {
                    found = true
                }
            }
        }
        return found
    }

    private fun extractMediaFromHtmlOrPacked(
        html: String,
        origin: String,
        serverLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val unpacked = unpackPackedScripts(html)
        val searchText = "$html\n$unpacked"
        val labelPrefix = if (serverLabel.isNotBlank()) serverLabel else "Multi"
        var emitted = false

        val mediaRegex = Regex("""file\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
        mediaRegex.findAll(searchText).map { it.groupValues[1] }.distinct().forEach { mediaUrl ->
            callback(
                newExtractorLink(
                    source = name,
                    name = labelPrefix,
                    url = mediaUrl,
                    type = if (mediaUrl.contains(".m3u8", ignoreCase = true)) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                ) {
                    this.referer = "$origin/"
                }
            )
            emitted = true
        }

        val subRegex = Regex("""file\s*:\s*["'](https?://[^"']+\.(?:vtt|srt)[^"']*)["']""", RegexOption.IGNORE_CASE)
        subRegex.findAll(searchText).map { it.groupValues[1] }.distinct().forEach { subUrl ->
            if (!subUrl.contains("_sli.vtt", ignoreCase = true)) {
                subtitleCallback(
                    newSubtitleFile(
                        lang = "English",
                        url = subUrl
                    )
                )
            }
        }

        return emitted
    }

    private fun unpackPackedScripts(html: String): String {
        val packerRegex = Regex(
            """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val out = StringBuilder()
        packerRegex.findAll(html).forEach { match ->
            val payload = match.groupValues[1]
                .replace("\\'", "'")
                .replace("\\\\", "\\")
            val radix = match.groupValues[2].toIntOrNull() ?: return@forEach
            val count = match.groupValues[3].toIntOrNull() ?: return@forEach
            val dict = match.groupValues[4].split("|")
            val unpacked = Regex("\\b\\w+\\b").replace(payload) { wordMatch ->
                val token = wordMatch.value
                val idx = decodeBaseRadix(token, radix)
                if (idx in 0 until count && idx < dict.size && dict[idx].isNotEmpty()) {
                    dict[idx]
                } else {
                    token
                }
            }
            out.append(unpacked).append('\n')
        }
        return out.toString()
    }

    private fun decodeBaseRadix(token: String, radix: Int): Int {
        var result = 0
        for (ch in token) {
            val digit = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'a'..'z' -> ch - 'a' + 10
                in 'A'..'Z' -> ch - 'A' + 36
                else -> return -1
            }
            if (digit >= radix) return -1
            result = result * radix + digit
        }
        return result
    }

    private fun normalizeEmbedUrl(rawUrl: String): String? {
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

    private fun parseArchiveCards(doc: Document): List<SearchResponse> {
        return doc.select("div.kira-anime").mapNotNull { kiraEl ->
            val cardRoot = kiraEl.parent()
            val href = cardRoot?.selectFirst("h2 a[href*='/anime/']")?.attr("href")?.trim()
                ?.ifBlank { null }
                ?: kiraEl.selectFirst("a[href*='/anime/']")?.attr("href")?.trim()
                ?: return@mapNotNull null
            val itemUrl = fixUrl(href)
            val title = cardRoot?.selectFirst("h2 a span[data-nt-title]")?.text()?.trim()?.ifBlank { null }
                ?: cardRoot?.selectFirst("h2 a span[data-en-title]")?.text()?.trim()?.ifBlank { null }
                ?: cardRoot?.selectFirst("h2 a")?.text()?.trim()?.ifBlank { null }
                ?: kiraEl.selectFirst("img")?.attr("alt")?.removeSuffix(" thumbnail")?.trim()
                ?: return@mapNotNull null
            val poster = kiraEl.selectFirst("img").extractImageUrl()
            val audioBadge = kiraEl.selectFirst("div.min-w-max span")?.text()?.trim().orEmpty()
            val epBadge = kiraEl.selectFirst("span.bg-accent-3")?.text()?.trim().orEmpty()
            val epCount = Regex("(\\d+)").find(epBadge)?.groupValues?.get(1)?.toIntOrNull()
            val typeLabel = cardRoot?.selectFirst("div.text-xs span.uppercase")?.text()?.trim().orEmpty()

            buildSearchResponse(
                title = title,
                url = itemUrl,
                poster = poster,
                typeLabel = typeLabel,
                audioBadge = "$audioBadge $title",
                episodeCount = epCount
            )
        }.distinctBy { it.url }
    }

    private fun hasNextArchivePage(doc: Document, currentPage: Int): Boolean {
        return doc.select("ul.page-numbers a[href]").any { a ->
            val pageNum = Regex("/page/(\\d+)/?")
                .find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            pageNum != null && pageNum > currentPage
        }
    }

    private fun buildSearchResponse(
        title: String,
        url: String,
        poster: String?,
        typeLabel: String,
        audioBadge: String,
        episodeCount: Int?
    ): SearchResponse {
        val dubStatus = resolveDubStatus(audioBadge, title)
        val normalizedType = typeLabel.trim().uppercase()
        return when (normalizedType) {
            "MOVIE" -> newAnimeSearchResponse(title, url, TvType.AnimeMovie) {
                posterUrl = poster
                addDubStatus(dubStatus)
            }
            "OVA", "ONA" -> newAnimeSearchResponse(title, url, TvType.OVA) {
                posterUrl = poster
                addDubStatus(dubStatus, episodeCount)
            }
            else -> newAnimeSearchResponse(title, url, TvType.Anime) {
                posterUrl = poster
                addDubStatus(dubStatus, episodeCount)
            }
        }
    }

    private fun resolveDubStatus(audioInfo: String, title: String): DubStatus {
        val combined = "$audioInfo $title"
        val hasSub = subRegex.containsMatchIn(combined)
        val hasDub = dubRegex.containsMatchIn(combined)
        return when {
            hasSub && !hasDub -> DubStatus.Subbed
            hasDub || combined.contains("Hindi", ignoreCase = true) -> DubStatus.Dubbed
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
