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

class AnimeVillaProvider : MainAPI() {
    override var mainUrl = "https://animevilla.org"
    override var name = "AnimeVilla"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val mapper = jacksonObjectMapper()

    @Volatile
    private var activeBaseUrl: String = mainUrl

    private fun siteHeaders(referer: String = "$activeBaseUrl/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Origin" to activeBaseUrl
    )

    override val mainPage = mainPageOf(
        "updated" to "Recently Updated",
        "popular" to "Most Popular",
        "status/ongoing" to "Ongoing Anime",
        "status/completed" to "Completed Anime",
        "anime-type/movie" to "Anime Movies",
        "status/anime-movies" to "Hindi Dubbed Movies",
        "genre/action" to "Action",
        "genre/adventure" to "Adventure",
        "genre/comedy" to "Comedy",
        "genre/drama" to "Drama",
        "genre/fantasy" to "Fantasy",
        "genre/isekai" to "Isekai",
        "genre/romance" to "Romance",
        "genre/shounen" to "Shounen"
    )

    private data class PackRef(
        val quality: String = "",
        val url: String = ""
    )

    private data class EpisodePayload(
        val ep: Int = 1,
        val season: Int = 1,
        val watchUrls: List<String> = emptyList(),
        val packUrls: List<PackRef> = emptyList()
    )

    private data class ParsedSectionPack(
        val season: Int,
        val epStart: Int?,
        val epEnd: Int?,
        val quality: String,
        val urls: List<String>
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val currentPage = page.coerceAtLeast(1)
        return when (request.data) {
            "updated" -> fetchAdvancedSearchHome(
                page = currentPage,
                title = request.name,
                singleParams = mapOf(
                    "paged" to currentPage,
                    "orderby" to "meta_value_num",
                    "meta_key" to "kiranime_anime_updated"
                ),
                fallbackArchive = null
            )
            "popular" -> fetchAdvancedSearchHome(
                page = currentPage,
                title = request.name,
                singleParams = mapOf(
                    "paged" to currentPage,
                    "orderby" to "meta_value_num",
                    "meta_key" to "total_kiranime_views"
                ),
                fallbackArchive = "status/completed"
            )
            else -> fetchArchiveHome(request.data, currentPage, request.name)
        }
    }

    private suspend fun fetchAdvancedSearchHome(
        page: Int,
        title: String,
        singleParams: Map<String, Any>,
        fallbackArchive: String?
    ): HomePageResponse {
        val base = ensureActiveBase()
        val payload = mapOf(
            "keyword" to "",
            "query" to "",
            "single" to singleParams,
            "tax" to emptyList<Any>()
        )
        val root = try {
            val res = app.post(
                "$base/wp-json/kiranime/v1/anime/advancedsearch",
                headers = siteHeaders("$base/"),
                json = payload
            )
            updateBaseFromResponseUrl(res.url)
            mapper.readTree(res.text)
        } catch (_: Exception) {
            null
        }

        val htmlData = root?.path("data")?.asText("").orEmpty()
        if (htmlData.isNotBlank()) {
            val totalPages = root?.path("pages")?.asInt(1) ?: 1
            val doc = Jsoup.parseBodyFragment(htmlData, activeBaseUrl)
            val items = parseArchiveCards(doc)
            return newHomePageResponse(
                listOf(HomePageList(title, items, isHorizontalImages = false)),
                hasNext = page < totalPages && items.isNotEmpty()
            )
        }

        if (fallbackArchive != null) {
            return fetchArchiveHome(fallbackArchive, page, title)
        }

        if (page > 1) return newHomePageResponse(emptyList(), false)
        val doc = fetchDocument("$base/") ?: return newHomePageResponse(emptyList(), false)
        val items = parseArchiveCards(doc)
        return newHomePageResponse(
            listOf(HomePageList(title, items, isHorizontalImages = false)),
            hasNext = false
        )
    }

    private suspend fun fetchArchiveHome(
        archivePath: String,
        page: Int,
        title: String
    ): HomePageResponse {
        val base = ensureActiveBase()
        val cleanPath = archivePath.trim('/')
        val url = if (page <= 1) {
            "$base/$cleanPath/"
        } else {
            "$base/$cleanPath/page/$page/"
        }
        val doc = fetchDocument(url) ?: return newHomePageResponse(emptyList(), false)
        val items = parseArchiveCards(doc)
        val hasNext = items.isNotEmpty() && hasNextArchivePage(doc, page)
        return newHomePageResponse(
            listOf(HomePageList(title, items, isHorizontalImages = false)),
            hasNext = hasNext
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val clean = query.trim()
        if (clean.isEmpty()) return emptyList()
        return searchViaAdvancedSearch(clean, 1).first.take(15)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val clean = query.trim()
        if (clean.isEmpty()) return newSearchResponseList(emptyList(), false)
        val currentPage = page.coerceAtLeast(1)

        val (apiItems, apiHasNext) = searchViaAdvancedSearch(clean, currentPage)
        if (apiItems.isNotEmpty()) {
            return newSearchResponseList(apiItems, hasNext = apiHasNext)
        }

        if (currentPage > 1) return newSearchResponseList(emptyList(), false)
        val base = ensureActiveBase()
        val searchUrl = "$base/search?s_keyword=${urlEncode(clean)}"
        val doc = fetchDocument(searchUrl) ?: return newSearchResponseList(emptyList(), false)
        val htmlItems = parseArchiveCards(doc)
        val hasNext = htmlItems.isNotEmpty() && hasNextArchivePage(doc, currentPage)
        return newSearchResponseList(htmlItems, hasNext = hasNext)
    }

    private suspend fun searchViaAdvancedSearch(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val base = ensureActiveBase()
        val payload = mapOf(
            "keyword" to query,
            "query" to "",
            "single" to mapOf("paged" to page),
            "tax" to emptyList<Any>()
        )
        val root = try {
            val res = app.post(
                "$base/wp-json/kiranime/v1/anime/advancedsearch",
                headers = siteHeaders("$base/"),
                json = payload
            )
            updateBaseFromResponseUrl(res.url)
            mapper.readTree(res.text)
        } catch (_: Exception) {
            return emptyList<SearchResponse>() to false
        }
        val htmlData = root.path("data").asText("")
        if (htmlData.isBlank()) return emptyList<SearchResponse>() to false
        val totalPages = root.path("pages").asInt(1)
        val doc = Jsoup.parseBodyFragment(htmlData, activeBaseUrl)
        val items = parseArchiveCards(doc)
        return items to (page < totalPages && items.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = rewriteToActiveBase(fixUrl(url))
        val doc = fetchDocument(pageUrl) ?: return null
        val canonicalUrl = doc.selectFirst("link[rel=canonical]")?.attr("href")?.trim()?.takeIf { it.startsWith("http") }
            ?: pageUrl

        val title = cleanDisplayTitle(
            doc.selectFirst("h1 span[data-en-title]")?.text()?.trim()?.ifBlank { null }
                ?: doc.selectFirst("h1 span[data-nt-title]")?.text()?.trim()?.ifBlank { null }
                ?: doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
                ?: return null
        )

        val poster = doc.selectFirst("div.anime-image img").extractImageUrl()
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.takeIf { it.startsWith("http") }
        val backdrop = doc.selectFirst("div.background-image img").extractImageUrl() ?: poster

        val synopsis = doc.selectFirst("div[data-synopsis]")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifBlank { null }

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
                        releaseYear = Regex("""\b(19|20)\d{2}\b""").find(valueText)?.value?.toIntOrNull()
                    }
                }
                label.startsWith("Score", ignoreCase = true) -> {
                    ratingText = valueText.substringBefore("/").trim().takeIf { it.isNotBlank() }
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

        if (releaseYear == null) {
            releaseYear = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
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
                ?.text()?.trim()?.uppercase()
            ?: if (title.contains("Movie", ignoreCase = true) || canonicalUrl.contains("movie", ignoreCase = true)) "MOVIE" else "TV"

        val recommendations = parseRecommendations(doc)

        val sliderEpisodes = doc.select("div.swiper-episode-anime div.swiper-slide a[href*='/watch/']")
            .mapNotNull { el ->
                val href = el.attr("href").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val epLabel = el.selectFirst("span.w-percentile")?.text()?.trim()?.ifBlank { null }
                    ?: el.attr("title").trim().ifBlank { null }
                val epNum = Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(epLabel.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""-episode-(\d+)/?$""", RegexOption.IGNORE_CASE)
                        .find(href)?.groupValues?.get(1)?.toIntOrNull()
                Triple(epNum, epLabel, rewriteToActiveBase(fixUrl(href)))
            }

        val watchNowLinks = doc.select("a[href*='/watch/']")
            .mapNotNull { a ->
                val href = a.attr("href").trim()
                if (href.isBlank() || href.contains("/feed/")) null else rewriteToActiveBase(fixUrl(href))
            }
            .distinct()

        val downloadPacks = parseDownloadSectionPacks(doc)

        val isMovie = metaType == "MOVIE" &&
            sliderEpisodes.size <= 1 &&
            downloadPacks.none { it.epStart != null && it.epEnd != null && it.epEnd > it.epStart }

        if (isMovie) {
            val moviePackRefs = downloadPacks.flatMap { pack ->
                pack.urls.map { u -> PackRef(quality = pack.quality, url = u) }
            }.distinctBy { it.url }

            val moviePayload = encodePayload(
                EpisodePayload(
                    ep = 1,
                    season = 1,
                    watchUrls = watchNowLinks,
                    packUrls = moviePackRefs
                )
            )
            if (watchNowLinks.isEmpty() && moviePackRefs.isEmpty()) return null

            return newMovieLoadResponse(title, canonicalUrl, TvType.AnimeMovie, moviePayload) {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                plot = synopsis
                year = releaseYear
                score = parsedScore
                tags = genres.distinct().takeIf { it.isNotEmpty() }
                this.recommendations = recommendations
            }
        }

        val packsByKey = LinkedHashMap<Pair<Int, Int>, MutableList<PackRef>>()
        val watchByKey = LinkedHashMap<Pair<Int, Int>, MutableList<String>>()
        val labelByKey = LinkedHashMap<Pair<Int, Int>, String>()

        if (sliderEpisodes.isNotEmpty()) {
            sliderEpisodes.reversed().forEachIndexed { idx, (parsedNum, epLabel, epUrl) ->
                val num = parsedNum ?: (idx + 1)
                val key = 1 to num
                watchByKey.getOrPut(key) { mutableListOf() }.add(epUrl)
                if (!epLabel.isNullOrBlank()) labelByKey[key] = epLabel
            }
        }

        for (pack in downloadPacks) {
            val start = pack.epStart ?: continue
            val end = (pack.epEnd ?: start).coerceAtLeast(start)
            if (end - start > 500) continue
            for (epNum in start..end) {
                val key = pack.season to epNum
                val list = packsByKey.getOrPut(key) { mutableListOf() }
                for (u in pack.urls) {
                    if (list.none { it.url == u }) {
                        list.add(PackRef(quality = pack.quality, url = u))
                    }
                }
            }
        }

        val allKeys = (packsByKey.keys + watchByKey.keys).distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))

        val episodeList = allKeys.map { key ->
            val (seasonNum, epNum) = key
            val payload = encodePayload(
                EpisodePayload(
                    ep = epNum,
                    season = seasonNum,
                    watchUrls = watchByKey[key].orEmpty().distinct(),
                    packUrls = packsByKey[key].orEmpty()
                )
            )
            val epName = labelByKey[key] ?: "Episode $epNum"
            newEpisode(payload) {
                this.name = epName
                this.season = seasonNum
                this.episode = epNum
                this.posterUrl = poster
            }
        }

        if (episodeList.isEmpty() && watchNowLinks.isNotEmpty()) {
            val singlePayload = encodePayload(
                EpisodePayload(ep = 1, season = 1, watchUrls = watchNowLinks, packUrls = emptyList())
            )
            val fallbackEp = newEpisode(singlePayload) {
                this.name = "Episode 1"
                this.season = 1
                this.episode = 1
                this.posterUrl = poster
            }
            return newAnimeLoadResponse(title, canonicalUrl, TvType.Anime) {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                plot = synopsis
                year = releaseYear
                score = parsedScore
                tags = genres.distinct().takeIf { it.isNotEmpty() }
                showStatus = resolvedStatus
                this.recommendations = recommendations
                addEpisodes(DubStatus.Dubbed, listOf(fallbackEp))
            }
        }

        if (episodeList.isEmpty()) return null

        val tvType = when (metaType) {
            "OVA" -> TvType.OVA
            "MOVIE" -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(title, canonicalUrl, tvType) {
            posterUrl = poster
            backgroundPosterUrl = backdrop
            plot = synopsis
            year = releaseYear
            score = parsedScore
            tags = genres.distinct().takeIf { it.isNotEmpty() }
            showStatus = resolvedStatus
            this.recommendations = recommendations
            addEpisodes(DubStatus.Dubbed, episodeList)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = decodePayload(data)
        var emittedAny = false
        val seenTargets = HashSet<String>()

        // 1. Resolve /watch/ page embeds first (e.g., multi-audio HLS player & hsastream)
        for (watchUrl in payload.watchUrls) {
            val resolvedWatch = rewriteToActiveBase(fixUrl(watchUrl))
            if (!seenTargets.add("watch:$resolvedWatch")) continue
            if (extractWatchPage(resolvedWatch, subtitleCallback, callback)) {
                emittedAny = true
            }
        }

        // 2. Resolve download-section pack links (host-agnostic: hsalinks.* -> hubcloud.* / any filehost)
        val sortedPacks = payload.packUrls.sortedByDescending { qualityRank(it.quality) }
        for (pack in sortedPacks) {
            val packUrl = normalizeUrl(pack.url) ?: continue
            if (!seenTargets.add("pack:$packUrl")) continue
            if (resolvePackOrHostUrl(packUrl, payload.ep, pack.quality, subtitleCallback, callback)) {
                emittedAny = true
            }
        }

        return emittedAny
    }

    // -------------------------------------------------------------------------
    // Watch-page embed & player extraction (host-agnostic)
    // -------------------------------------------------------------------------

    private suspend fun extractWatchPage(
        watchUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = fetchDocument(watchUrl) ?: return false
        var found = false
        val seenEmbeds = HashSet<String>()

        doc.select("[data-embed-id]").forEach { el ->
            val raw = el.attr("data-embed-id").trim()
            if (!raw.contains(":")) return@forEach
            val parts = raw.split(":", limit = 2)
            val label = decodeBase64(parts[0]).trim().ifBlank { el.text().trim() }.ifBlank { name }
            val embedUrl = normalizeUrl(decodeBase64(parts[1])) ?: return@forEach
            if (!seenEmbeds.add(embedUrl)) return@forEach

            if (extractAnyEmbed(embedUrl, label, watchUrl, subtitleCallback, callback, depth = 0)) {
                found = true
            }
        }

        doc.select("iframe[src], iframe[data-src], iframe[data-litespeed-src]").forEach { iframe ->
            val raw = iframe.attr("data-litespeed-src").trim()
                .ifBlank { iframe.attr("data-src").trim() }
                .ifBlank { iframe.attr("src").trim() }
            val iframeUrl = normalizeUrl(raw) ?: return@forEach
            if (!seenEmbeds.add(iframeUrl)) return@forEach

            if (extractAnyEmbed(iframeUrl, name, watchUrl, subtitleCallback, callback, depth = 0)) {
                found = true
            }
        }

        return found
    }

    private suspend fun extractAnyEmbed(
        embedUrl: String,
        serverLabel: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ): Boolean {
        if (depth > 2) return false
        val cleanLabel = serverLabel.ifBlank { name }

        if (Regex("""\.(?:m3u8|mp4|mkv)(?:$|\?)""", RegexOption.IGNORE_CASE).containsMatchIn(embedUrl)) {
            val isHls = embedUrl.contains(".m3u8", ignoreCase = true)
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name - $cleanLabel",
                    url = embedUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = refererUrl
                }
            )
            return true
        }

        val playerParam = extractQueryParam(embedUrl, "player")
        if (!playerParam.isNullOrBlank()) {
            if (extractBase64PlayerParam(embedUrl, playerParam, cleanLabel, subtitleCallback, callback)) {
                return true
            }
        }

        if (embedUrl.contains("#") && embedUrl.substringAfter("#").isNotBlank()) {
            val resolvedHashTarget = resolveHashApiPlayer(embedUrl)
            if (resolvedHashTarget != null) {
                return extractAnyEmbed(resolvedHashTarget, cleanLabel, embedUrl, subtitleCallback, callback, depth + 1)
            }
        }

        if (isHubCloudHost(embedUrl)) {
            if (resolveHubCloud(embedUrl, "", refererUrl, subtitleCallback, callback)) {
                return true
            }
        }

        val builtIn = try {
            loadExtractor(embedUrl, refererUrl, subtitleCallback, callback)
        } catch (_: Exception) {
            false
        }
        if (builtIn) return true

        if (Regex("""/e/[A-Za-z0-9_-]{6,}""").containsMatchIn(embedUrl)) {
            if (extractVidaraStyleEmbed(embedUrl, cleanLabel, subtitleCallback, callback)) {
                return true
            }
        }

        val pageRes = try {
            app.get(
                embedUrl,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to refererUrl),
                allowRedirects = true
            )
        } catch (_: Exception) {
            return false
        }
        if (pageRes.code !in 200..299) return false

        val origin = extractOrigin(pageRes.url) ?: extractOrigin(embedUrl) ?: activeBaseUrl
        val body = pageRes.text
        if (extractMediaFromHtmlOrPacked(body, origin, cleanLabel, subtitleCallback, callback)) {
            return true
        }

        val nestedDoc = Jsoup.parse(body, pageRes.url)
        for (nestedIframe in nestedDoc.select("iframe[src], iframe[data-src]")) {
            val rawSrc = nestedIframe.attr("data-src").trim().ifBlank { nestedIframe.attr("src").trim() }
            val absSrc = resolveRelativeUrl(rawSrc, origin) ?: continue
            if (absSrc != embedUrl && extractAnyEmbed(absSrc, cleanLabel, pageRes.url, subtitleCallback, callback, depth + 1)) {
                return true
            }
        }

        return false
    }

    private suspend fun extractBase64PlayerParam(
        embedUrl: String,
        encodedParam: String,
        serverLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val jsonText = decodeBase64(encodedParam)
        if (!jsonText.startsWith("{") && !jsonText.startsWith("[")) return false
        val root = try {
            mapper.readTree(jsonText)
        } catch (_: Exception) {
            return false
        }

        val origin = extractOrigin(embedUrl)?.let { "$it/" } ?: "$activeBaseUrl/"
        var emitted = false

        val qualities = if (root.isArray) root else root.path("qualities")
        if (qualities.isArray) {
            for (q in qualities) {
                val streamUrl = normalizeUrl(q.path("url").asText("")) ?: continue
                val qLabel = q.path("label").asText("").trim().ifBlank { "Auto" }
                val isHls = streamUrl.contains(".m3u8", ignoreCase = true)
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name - $serverLabel ($qLabel)",
                        url = streamUrl,
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = getQualityFromName(qLabel)
                        this.referer = origin
                    }
                )
                emitted = true
            }
        }

        val subs = root.path("subtitles")
        if (subs.isArray) {
            for (sub in subs) {
                val subUrl = normalizeUrl(sub.path("url").asText("")) ?: continue
                val subLang = sub.path("label").asText("").trim()
                    .ifBlank { sub.path("lang").asText("English").trim() }
                subtitleCallback(newSubtitleFile(lang = subLang, url = subUrl))
            }
        }

        return emitted
    }

    private suspend fun resolveHashApiPlayer(embedUrl: String): String? {
        val origin = extractOrigin(embedUrl) ?: return null
        val fileId = embedUrl.substringAfter("#", "").trim()
        if (fileId.isEmpty()) return null
        val apiUrl = "$origin/api/public/resolve?file1=${urlEncode(fileId)}"
        return try {
            val res = app.get(apiUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to embedUrl))
            if (res.code !in 200..299) return null
            val root = mapper.readTree(res.text)
            normalizeUrl(root.path("url").asText(""))
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun extractVidaraStyleEmbed(
        embedUrl: String,
        serverLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = extractOrigin(embedUrl) ?: return false
        val fileCode = Regex("""/(?:e|v)/([A-Za-z0-9_-]+)""").find(embedUrl)?.groupValues?.get(1) ?: return false
        val root = try {
            val res = app.post(
                "$origin/api/stream",
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$origin/e/$fileCode", "Origin" to origin),
                json = mapOf("filecode" to fileCode, "device" to "desktop")
            )
            if (res.code !in 200..299) return false
            mapper.readTree(res.text)
        } catch (_: Exception) {
            return false
        }
        val streamUrl = normalizeUrl(root.path("streaming_url").asText("")) ?: return false
        callback(
            newExtractorLink(
                source = name,
                name = "$name - $serverLabel",
                url = streamUrl,
                type = if (streamUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$origin/"
            }
        )
        root.path("subtitles").forEach { sub ->
            val subUrl = normalizeUrl(sub.path("file_path").asText("")) ?: return@forEach
            val subLang = sub.path("language").asText("English").trim().ifBlank { "English" }
            subtitleCallback(newSubtitleFile(lang = subLang, url = subUrl))
        }
        return true
    }

    // -------------------------------------------------------------------------
    // Download-section pack & HubCloud/filehost resolution (host-agnostic)
    // -------------------------------------------------------------------------

    private suspend fun resolvePackOrHostUrl(
        rawUrl: String,
        targetEpNum: Int,
        qualityLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isHubCloudHost(rawUrl)) {
            return resolveHubCloud(rawUrl, qualityLabel, "$activeBaseUrl/", subtitleCallback, callback)
        }

        val res = try {
            app.get(
                rawUrl,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$activeBaseUrl/"),
                allowRedirects = true
            )
        } catch (_: Exception) {
            return false
        }
        if (res.code !in 200..299) return false

        val finalUrl = res.url
        val finalOrigin = extractOrigin(finalUrl) ?: extractOrigin(rawUrl) ?: activeBaseUrl
        val ct = (res.headers["Content-Type"] ?: res.headers["content-type"]).orEmpty()

        if (ct.contains("video/", ignoreCase = true) || ct.contains("application/vnd.apple.mpegurl", ignoreCase = true)) {
            callback(
                newExtractorLink(
                    source = name,
                    name = formatQualityLinkTitle(name, qualityLabel),
                    url = finalUrl,
                    type = if (ct.contains("mpegurl", ignoreCase = true) || finalUrl.contains(".m3u8", ignoreCase = true)) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                ) {
                    this.quality = getQualityFromName(qualityLabel)
                    this.referer = ""
                }
            )
            return true
        }

        val doc = res.document
        if (isHubCloudPage(finalUrl, doc)) {
            return resolveHubCloudFromDoc(doc, finalUrl, qualityLabel, subtitleCallback, callback)
        }

        val candidateUrls = selectMatchingHostLinks(doc, finalOrigin, targetEpNum)
        if (candidateUrls.isEmpty()) {
            return try {
                loadExtractor(finalUrl, "$activeBaseUrl/", subtitleCallback, callback)
            } catch (_: Exception) {
                false
            }
        }

        var emitted = false
        for ((btnLabel, hostUrl) in candidateUrls) {
            if (isHubCloudHost(hostUrl)) {
                if (resolveHubCloud(hostUrl, qualityLabel, finalUrl, subtitleCallback, callback)) {
                    emitted = true
                }
            } else {
                val handled = try {
                    loadExtractor(hostUrl, finalUrl, subtitleCallback, callback)
                } catch (_: Exception) {
                    false
                }
                if (handled) {
                    emitted = true
                } else if (extractAnyEmbed(hostUrl, btnLabel, finalUrl, subtitleCallback, callback, depth = 0)) {
                    emitted = true
                }
            }
        }
        return emitted
    }

    private fun selectMatchingHostLinks(
        doc: Document,
        origin: String,
        targetEpNum: Int
    ): List<Pair<String, String>> {
        val exactEpLinks = mutableListOf<Pair<String, String>>()
        val nonZipSingleLinks = mutableListOf<Pair<String, String>>()

        val cards = doc.select("div.card")
        if (cards.isNotEmpty()) {
            for (card in cards) {
                val heading = card.selectFirst("h5, h4, h3, .card-title")?.text()?.trim().orEmpty()
                val isZipHeading = heading.contains("ZIP", ignoreCase = true)

                val anchors = card.select("a[href]").mapNotNull { a ->
                    val btnText = a.text().trim()
                    val href = resolveRelativeUrl(a.attr("href"), origin) ?: return@mapNotNull null
                    if (isIgnoredIndexLink(href, btnText)) return@mapNotNull null
                    btnText to href
                }
                if (anchors.isEmpty()) continue

                val epNums = Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .findAll(heading)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }
                    .toList()

                if (!isZipHeading && epNums.size == 1 && epNums[0] == targetEpNum) {
                    exactEpLinks.addAll(anchors)
                } else if (!isZipHeading && epNums.isEmpty()) {
                    nonZipSingleLinks.addAll(anchors)
                }
            }
        }

        if (exactEpLinks.isNotEmpty()) return exactEpLinks.distinctBy { it.second }
        if (nonZipSingleLinks.isNotEmpty()) return nonZipSingleLinks.distinctBy { it.second }

        val fallbackAnchors = mutableListOf<Pair<String, String>>()
        doc.select("a[href]").forEach { a ->
            val btnText = a.text().trim()
            val href = resolveRelativeUrl(a.attr("href"), origin) ?: return@forEach
            if (!isIgnoredIndexLink(href, btnText) && !btnText.contains("ZIP", ignoreCase = true)) {
                fallbackAnchors.add(btnText to href)
            }
        }
        return fallbackAnchors.distinctBy { it.second }
    }

    private suspend fun resolveHubCloud(
        driveUrl: String,
        qualityLabel: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val step1Res = try {
            app.get(
                driveUrl,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer),
                allowRedirects = true
            )
        } catch (_: Exception) {
            return false
        }
        if (step1Res.code !in 200..299) return false
        return resolveHubCloudFromDoc(step1Res.document, step1Res.url, qualityLabel, subtitleCallback, callback)
    }

    private suspend fun resolveHubCloudFromDoc(
        step1Doc: Document,
        step1Url: String,
        qualityLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = extractOrigin(step1Url) ?: "https://hubcloud.mov"
        val step1Html = step1Doc.outerHtml()

        val hubPhpUrl = Regex("""var\s+url\s*=\s*['"](https?://[^'"]+)['"]""")
            .find(step1Html)?.groupValues?.get(1)
            ?: step1Doc.selectFirst("a#download[href]")?.attr("href")?.trim()?.let {
                resolveRelativeUrl(it, origin)
            }
            ?: step1Doc.select("a[href]")
                .mapNotNull { resolveRelativeUrl(it.attr("href"), origin) }
                .firstOrNull {
                    it.contains("hubcloud.php", ignoreCase = true) ||
                        it.contains("gamerxyt", ignoreCase = true)
                }

        val step2Url: String
        val step2Doc: Document
        if (!hubPhpUrl.isNullOrBlank()) {
            val res = try {
                app.get(
                    hubPhpUrl,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to step1Url),
                    allowRedirects = true
                )
            } catch (_: Exception) {
                return false
            }
            if (res.code !in 200..299) return false
            step2Url = res.url
            step2Doc = res.document
        } else {
            step2Url = step1Url
            step2Doc = step1Doc
        }

        val parsedQuality = getQualityFromName(qualityLabel)
        var emitted = false
        val emittedUrls = HashSet<String>()
        val anchors = step2Doc.select("a[href]")

        // 1. 10Gbps / Fast direct server (TLD-independent: pixel.hubcloud.* / gpdl.hubcloud.* / "10Gbps" button)
        for (anchor in anchors) {
            val href = resolveRelativeUrl(anchor.attr("href"), extractOrigin(step2Url) ?: origin) ?: continue
            val text = anchor.text().trim()
            if (href.contains("pixel.hubcloud.", ignoreCase = true) ||
                href.contains("gpdl.hubcloud.", ignoreCase = true) ||
                text.contains("10Gbps", ignoreCase = true)
            ) {
                val directUrl = followRedirectChainToStream(href, step2Url)
                if (directUrl != null && emittedUrls.add(directUrl)) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = formatQualityLinkTitle("$name 10Gbps", qualityLabel),
                            url = directUrl,
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

        // 2. FSL / Cloudflare R2 direct mirror (probe 1 byte to discard expired 403 links)
        for (anchor in anchors) {
            val href = resolveRelativeUrl(anchor.attr("href"), extractOrigin(step2Url) ?: origin) ?: continue
            val text = anchor.text().trim()
            if (href.contains("r2.cloudflarestorage.com", ignoreCase = true) ||
                (text.contains("FSL", ignoreCase = true) && href.startsWith("http"))
            ) {
                if (emittedUrls.add(href) && probeRangeByte(href)) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = formatQualityLinkTitle("$name FSL", qualityLabel),
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

        // 3. HBPlay / encoded ?u= stream fallback
        for (anchor in anchors) {
            val href = resolveRelativeUrl(anchor.attr("href"), extractOrigin(step2Url) ?: origin) ?: continue
            if (href.contains("hbplay.", ignoreCase = true)) {
                val uParam = extractQueryParam(href, "u") ?: continue
                val decoded = normalizeUrl(decodeBase64(uParam)) ?: continue
                if (emittedUrls.add(decoded) && probeRangeByte(decoded)) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = formatQualityLinkTitle("$name HBPlay", qualityLabel),
                            url = decoded,
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

        // 4. PixelDrain (TLD-independent: pixeldrain.*/u/<id>), pre-checked via /api/file/<id>/info
        for (anchor in anchors) {
            val href = resolveRelativeUrl(anchor.attr("href"), extractOrigin(step2Url) ?: origin) ?: continue
            if (href.contains("pixeldrain.", ignoreCase = true)) {
                val pdOrigin = extractOrigin(href) ?: continue
                val fileId = Regex("""/u/([A-Za-z0-9]+)""").find(href)?.groupValues?.get(1) ?: continue
                if (isPixelDrainAlive(pdOrigin, fileId)) {
                    val pdApiFile = "$pdOrigin/api/file/$fileId"
                    if (emittedUrls.add(pdApiFile)) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = formatQualityLinkTitle("$name PixelDrain", qualityLabel),
                                url = pdApiFile,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = parsedQuality
                                this.referer = "$pdOrigin/"
                            }
                        )
                        emitted = true
                    }
                }
            }
        }

        // 5. Delegate any other non-ad mirror buttons to CloudStream's built-in extractors
        if (!emitted) {
            for (anchor in anchors) {
                val href = resolveRelativeUrl(anchor.attr("href"), extractOrigin(step2Url) ?: origin) ?: continue
                val text = anchor.text().trim()
                if (isIgnoredHubCloudButton(href, text) || !emittedUrls.add(href)) continue
                val handled = try {
                    loadExtractor(href, step2Url, subtitleCallback, callback)
                } catch (_: Exception) {
                    false
                }
                if (handled) emitted = true
            }
        }

        return emitted
    }

    private suspend fun followRedirectChainToStream(startUrl: String, referer: String): String? {
        var currentUrl = startUrl
        var currentReferer = referer
        for (hop in 0 until 7) {
            val fromQuery = extractGoogleOrDirectFromParam(currentUrl)
            if (fromQuery != null) {
                return if (probeStreamNotZip(fromQuery)) fromQuery else null
            }

            val res = try {
                app.get(
                    currentUrl,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to currentReferer),
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
                val fromLocParam = extractGoogleOrDirectFromParam(absLocation)
                if (fromLocParam != null) {
                    return if (probeStreamNotZip(fromLocParam)) fromLocParam else null
                }
                currentReferer = currentUrl
                currentUrl = absLocation
                continue
            }

            if (res.code in 200..299) {
                val ct = (res.headers["Content-Type"] ?: res.headers["content-type"]).orEmpty()
                if ((ct.contains("video/", ignoreCase = true) || ct.contains("octet-stream", ignoreCase = true)) &&
                    !ct.contains("zip", ignoreCase = true)
                ) {
                    return currentUrl
                }
                val bodyGoogle = extractGoogleOrDirectFromParam(res.text)
                if (bodyGoogle != null && probeStreamNotZip(bodyGoogle)) {
                    return bodyGoogle
                }
            }
            break
        }
        return null
    }

    private fun extractGoogleOrDirectFromParam(text: String): String? {
        val linkParam = extractQueryParam(text, "link")
        if (!linkParam.isNullOrBlank() && linkParam.startsWith("http", ignoreCase = true)) {
            if (!linkParam.endsWith(".zip", ignoreCase = true)) {
                return linkParam.trim()
            }
        }
        val gMatch = Regex("""https://video-downloads\.googleusercontent\.com/[^\s"'<>]+""")
            .find(text)?.value?.trim()
        return gMatch?.replace("&amp;", "&")
    }

    private suspend fun probeStreamNotZip(url: String): Boolean {
        return try {
            val res = app.get(
                url,
                headers = mapOf("User-Agent" to USER_AGENT, "Range" to "bytes=0-0"),
                allowRedirects = true
            )
            val ct = (res.headers["Content-Type"] ?: res.headers["content-type"]).orEmpty()
            val disp = (res.headers["Content-Disposition"] ?: res.headers["content-disposition"]).orEmpty()
            if (ct.contains("zip", ignoreCase = true) || disp.contains(".zip\"", ignoreCase = true) || disp.endsWith(".zip", ignoreCase = true)) {
                return false
            }
            res.code in 200..299 && !ct.contains("text/html", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun probeRangeByte(url: String): Boolean {
        return try {
            val res = app.get(
                url,
                headers = mapOf("User-Agent" to USER_AGENT, "Range" to "bytes=0-0"),
                allowRedirects = true
            )
            val ct = (res.headers["Content-Type"] ?: res.headers["content-type"]).orEmpty()
            res.code in 200..299 &&
                !ct.contains("text/html", ignoreCase = true) &&
                !ct.contains("zip", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun isPixelDrainAlive(origin: String, fileId: String): Boolean {
        return try {
            val res = app.get(
                "$origin/api/file/$fileId/info",
                headers = mapOf("User-Agent" to USER_AGENT)
            )
            res.code == 200 && res.text.contains("\"success\":true") && !res.text.contains(".zip\"")
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------------
    // HTML & JS parsing helpers
    // -------------------------------------------------------------------------

    private fun parseArchiveCards(doc: Document): List<SearchResponse> {
        return doc.select("div.kira-anime").mapNotNull { kiraEl ->
            val cardRoot = kiraEl.parent()
            val titleAnchor = cardRoot?.selectFirst("h3 a[href*='/anime/'], h2 a[href*='/anime/']")
            val rawHref = titleAnchor?.attr("href")?.trim()?.ifBlank { null }
                ?: kiraEl.selectFirst("a[href*='/anime/']")?.attr("href")?.trim()
                ?: return@mapNotNull null

            val itemUrl = rewriteToActiveBase(fixUrl(rawHref))
            val rawTitle = titleAnchor?.selectFirst("span[data-en-title]")?.text()?.trim()?.ifBlank { null }
                ?: titleAnchor?.selectFirst("span[data-nt-title]")?.text()?.trim()?.ifBlank { null }
                ?: titleAnchor?.text()?.trim()?.ifBlank { null }
                ?: kiraEl.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }
                ?: return@mapNotNull null
            val title = cleanDisplayTitle(rawTitle)

            val poster = kiraEl.selectFirst("img").extractImageUrl()
            val epBadge = kiraEl.selectFirst("span.bg-accent-3")?.text()?.trim().orEmpty()
            val epCount = Regex("""(\d+)""").find(epBadge)?.groupValues?.get(1)?.toIntOrNull()
            val typeBadge = kiraEl.selectFirst("div.min-w-max span")?.text()?.trim().orEmpty()

            val tvType = if (typeBadge.equals("Movie", ignoreCase = true) ||
                title.contains("Movie", ignoreCase = true)
            ) {
                TvType.AnimeMovie
            } else {
                TvType.Anime
            }

            newAnimeSearchResponse(title, itemUrl, tvType) {
                this.posterUrl = poster
                addDubStatus(DubStatus.Dubbed, epCount)
            }
        }.distinctBy { it.url }
    }

    private fun parseRecommendations(doc: Document): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        doc.select("ul.bg-overlay li").forEach { li ->
            val linkEl = li.selectFirst("h3 a[href*='/anime/'], h2 a[href*='/anime/']") ?: return@forEach
            val recUrl = rewriteToActiveBase(fixUrl(linkEl.attr("href")))
            if (!seen.add(recUrl)) return@forEach
            val rawTitle = linkEl.selectFirst("span[data-en-title]")?.text()?.trim()?.ifBlank { null }
                ?: linkEl.selectFirst("span[data-nt-title]")?.text()?.trim()?.ifBlank { null }
                ?: linkEl.text().trim()
            if (rawTitle.isBlank()) return@forEach
            val recTitle = cleanDisplayTitle(rawTitle)
            val recPoster = li.selectFirst("img").extractImageUrl()
            val recType = li.selectFirst("div.text-spec span.uppercase")?.text()?.trim().orEmpty()
            val tvType = if (recType.equals("MOVIE", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime
            list.add(
                newAnimeSearchResponse(recTitle, recUrl, tvType) {
                    this.posterUrl = recPoster
                }
            )
        }
        return list
    }

    private fun parseDownloadSectionPacks(doc: Document): List<ParsedSectionPack> {
        val packs = mutableListOf<ParsedSectionPack>()
        doc.select("div.download-section-item").forEach { item ->
            val resText = item.selectFirst(".download-section-item-res")?.text()?.trim().orEmpty()
            val urls = item.select("a[href]")
                .mapNotNull { normalizeUrl(it.attr("href")) }
                .distinct()
            if (urls.isEmpty()) return@forEach

            val season = Regex("""(?:^|\b)(?:S|Season\s*)(\d+)\b""", RegexOption.IGNORE_CASE)
                .find(resText)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1

            val rangeMatch = Regex("""(?:Episode|Episodes|Ep)\s*(\d+)\s*[-–to]+\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(resText)
            val singleMatch = Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(resText)

            val epStart = rangeMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: singleMatch?.groupValues?.get(1)?.toIntOrNull()
            val epEnd = rangeMatch?.groupValues?.get(2)?.toIntOrNull()
                ?: epStart

            val quality = Regex("""\b(2160p|4K|1080p|720p|480p|360p)\b""", RegexOption.IGNORE_CASE)
                .findAll(resText)
                .map { it.groupValues[1] }
                .lastOrNull()
                .orEmpty()

            packs.add(
                ParsedSectionPack(
                    season = season,
                    epStart = epStart,
                    epEnd = epEnd,
                    quality = quality,
                    urls = urls
                )
            )
        }
        return packs
    }

    private fun hasNextArchivePage(doc: Document, currentPage: Int): Boolean {
        val nextLink = doc.selectFirst("a.next.page-numbers, nav.navigation a:contains(Next)")
        if (nextLink != null) return true
        return doc.select("a.page-numbers[href*='/page/']").any { el ->
            val p = Regex("""/page/(\d+)/""").find(el.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            p != null && p > currentPage
        }
    }

    private suspend fun extractMediaFromHtmlOrPacked(
        html: String,
        origin: String,
        serverLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val unpacked = unpackPackedScripts(html)
        val searchText = "$html\n$unpacked"
        var emitted = false
        val seen = HashSet<String>()

        val mediaRegex = Regex(
            """(?:file|src|url)\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""",
            RegexOption.IGNORE_CASE
        )
        for (match in mediaRegex.findAll(searchText)) {
            val mediaUrl = match.groupValues[1].replace("\\/", "/").trim()
            if (!seen.add(mediaUrl)) continue
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name - $serverLabel",
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

        val subRegex = Regex(
            """(?:file|src|url)\s*[:=]\s*["'](https?://[^"']+\.(?:vtt|srt)[^"']*)["']""",
            RegexOption.IGNORE_CASE
        )
        for (match in subRegex.findAll(searchText)) {
            val subUrl = match.groupValues[1].replace("\\/", "/").trim()
            if (seen.add(subUrl) && !subUrl.contains("_sli.vtt", ignoreCase = true)) {
                subtitleCallback(newSubtitleFile(lang = "English", url = subUrl))
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
            val unpacked = Regex("""\b\w+\b""").replace(payload) { wordMatch ->
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

    // -------------------------------------------------------------------------
    // Base domain discovery & utility helpers
    // -------------------------------------------------------------------------

    private fun ensureActiveBase(): String = activeBaseUrl

    private suspend fun fetchDocument(url: String): Document? {
        return try {
            val res = app.get(url, headers = siteHeaders(), allowRedirects = true)
            if (res.code !in 200..299) return null
            updateBaseFromResponseUrl(res.url)
            val doc = res.document
            val canonicalOrigin = doc.selectFirst("link[rel=canonical]")?.attr("href")?.let { extractOrigin(it) }
            if (!canonicalOrigin.isNullOrBlank() && canonicalOrigin != activeBaseUrl) {
                activeBaseUrl = canonicalOrigin
            }
            doc
        } catch (_: Exception) {
            null
        }
    }

    private fun updateBaseFromResponseUrl(finalUrl: String) {
        val origin = extractOrigin(finalUrl) ?: return
        if (!origin.equals(activeBaseUrl, ignoreCase = true) && origin.contains("animevilla", ignoreCase = true)) {
            activeBaseUrl = origin
        }
    }

    private fun rewriteToActiveBase(url: String): String {
        val currentOrigin = extractOrigin(url) ?: return url
        val mainOrigin = extractOrigin(mainUrl)
        return if (mainOrigin != null && currentOrigin.equals(mainOrigin, ignoreCase = true) && activeBaseUrl != mainOrigin) {
            url.replaceFirst(currentOrigin, activeBaseUrl)
        } else {
            url
        }
    }

    private fun isHubCloudHost(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("hubcloud.") && !lower.contains("pixel.hubcloud.") && !lower.contains("gpdl.hubcloud.")
    }

    private fun isHubCloudPage(url: String, doc: Document): Boolean {
        if (isHubCloudHost(url)) return true
        return doc.selectFirst("a#download[href*='hubcloud.php'], a#download[href*='gamerxyt']") != null
    }

    private fun isIgnoredIndexLink(href: String, label: String): Boolean {
        val lowerHref = href.lowercase()
        val lowerLabel = label.lowercase()
        return lowerHref.contains("t.me/") ||
            lowerHref.contains("telegram.dog") ||
            lowerHref.contains("animevilla.") ||
            lowerHref.contains("/solution") ||
            (lowerHref.contains("hsalinks.") && lowerHref.trimEnd('/').length <= 22) ||
            lowerLabel == "home" ||
            lowerLabel.contains("telegram") ||
            lowerLabel == "hsa links"
    }

    private fun isIgnoredHubCloudButton(href: String, label: String): Boolean {
        val lowerHref = href.lowercase()
        val lowerLabel = label.lowercase()
        return lowerHref.contains("/drive/admin") ||
            lowerHref.contains("hubcloudreport") ||
            lowerHref.contains("t.me/") ||
            lowerHref.contains("/tg/go") ||
            lowerHref.contains("one.one.one.one") ||
            lowerHref.contains("winexch") ||
            lowerHref.contains("tinyurl") ||
            lowerHref.contains("snvhost") ||
            lowerHref.contains("google.com/search") ||
            lowerHref.contains("fuckingfast.") ||
            lowerLabel == "login" ||
            lowerLabel == "vpn" ||
            lowerLabel.contains("telegram") ||
            lowerLabel.contains("idm") ||
            lowerLabel.contains("ida")
    }

    private fun Element?.extractImageUrl(): String? {
        if (this == null) return null
        val candidates = listOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("data-original"),
            attr("src")
        )
        for (raw in candidates) {
            val trimmed = raw.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("data:", ignoreCase = true)) {
                return fixUrl(trimmed)
            }
        }
        return null
    }

    private fun cleanDisplayTitle(raw: String): String {
        return raw.replace(Regex("""\s*\(\d{1,4}\)\s*$"""), "")
            .replace(Regex("""\s+Watch\s+.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { raw.trim() }
    }

    private fun qualityRank(q: String): Int {
        return when {
            q.contains("2160", ignoreCase = true) || q.contains("4k", ignoreCase = true) -> 4
            q.contains("1080", ignoreCase = true) -> 3
            q.contains("720", ignoreCase = true) -> 2
            q.contains("480", ignoreCase = true) -> 1
            else -> 0
        }
    }

    private fun formatQualityLinkTitle(prefix: String, qualityLabel: String): String {
        val cleanQ = qualityLabel.trim()
        return if (cleanQ.isNotEmpty()) "$prefix [$cleanQ]" else prefix
    }

    private fun encodePayload(payload: EpisodePayload): String {
        return try {
            "avdata:" + mapper.writeValueAsString(payload)
        } catch (_: Exception) {
            "$activeBaseUrl/"
        }
    }

    private fun decodePayload(data: String): EpisodePayload {
        if (data.startsWith("avdata:")) {
            val json = data.removePrefix("avdata:")
            try {
                return mapper.readValue(json, EpisodePayload::class.java)
            } catch (_: Exception) {
                // fall through
            }
        }
        return EpisodePayload(
            ep = 1,
            season = 1,
            watchUrls = listOf(fixUrl(data)),
            packUrls = emptyList()
        )
    }

    private fun normalizeUrl(raw: String?): String? {
        val trimmed = raw?.trim()?.replace("&amp;", "&").orEmpty()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> null
        }
    }

    private fun resolveRelativeUrl(rawUrl: String, origin: String): String? {
        val trimmed = rawUrl.trim().replace("&amp;", "&")
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
        val query = url.substringAfter('?', "")
        if (query.isBlank()) return null
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            val rawKey = if (idx >= 0) pair.substring(0, idx) else pair
            if (rawKey == key) {
                val rawVal = if (idx >= 0) pair.substring(idx + 1) else ""
                return try {
                    URLDecoder.decode(rawVal, "UTF-8")
                } catch (_: Exception) {
                    rawVal
                }
            }
        }
        return null
    }

    private fun urlEncode(value: String): String {
        return try {
            URLEncoder.encode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }

    private fun decodeBase64(input: String): String {
        val cleaned = input.trim()
            .filter { !it.isWhitespace() }
            .replace('-', '+')
            .replace('_', '/')
        if (cleaned.isEmpty()) return ""
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val bytes = ArrayList<Byte>((cleaned.length * 3) / 4)
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
                bytes.add(((buffer shr bitsCollected) and 0xFF).toByte())
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
