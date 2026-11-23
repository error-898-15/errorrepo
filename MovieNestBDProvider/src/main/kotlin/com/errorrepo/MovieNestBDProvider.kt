package com.errorrepo

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class MovieNestBDProvider : MainAPI() {
    override var mainUrl = "https://movienestbd.best"
    override var name = "MovieNestBD"
    override var lang = "bn"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie
    )

    private val mapper = jacksonObjectMapper()

    private val defaultHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    private data class RawMediaLink(
        val name: String,
        val link: String,
        val quality: String,
        val language: String,
        val size: String
    )

    private data class SourceSpec(
        val url: String = "",
        val label: String = "",
        val quality: String = "",
        val language: String = "",
        val size: String = ""
    )

    private data class LoadPayload(
        val pageUrl: String = "",
        val sources: List<SourceSpec> = emptyList()
    )

    private data class EpisodeBucket(
        val season: Int,
        val episode: Int,
        var title: String,
        val sources: MutableList<SourceSpec>
    )

    override val mainPage = mainPageOf(
        "updates" to "Latest Updates",
        "language/bengali" to "Bengali / Bangla",
        "movies" to "Movies",
        "series" to "Web Series",
        "anime" to "Anime",
        "ongoing" to "Ongoing Series",
        "category/bollywood" to "Bollywood",
        "category/hollywood" to "Hollywood",
        "category/south-indian" to "South Indian",
        "category/korean" to "Korean",
        "category/foreign" to "Foreign"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val currentPage = page.coerceAtLeast(1)
        val cleanPath = request.data.trim().trimStart('/')
        val url = if (currentPage <= 1) {
            "$mainUrl/$cleanPath"
        } else {
            "$mainUrl/$cleanPath?page=$currentPage"
        }

        val doc = try {
            app.get(url, headers = defaultHeaders).document
        } catch (_: Exception) {
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        val items = parseCardGrid(doc)
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
        val encodedQuery = try {
            URLEncoder.encode(cleanQuery, "UTF-8")
        } catch (_: Exception) {
            cleanQuery
        }

        val searchUrl = if (currentPage <= 1) {
            "$mainUrl/search?q=$encodedQuery"
        } else {
            "$mainUrl/search?q=$encodedQuery&page=$currentPage"
        }

        val doc = try {
            app.get(searchUrl, headers = defaultHeaders).document
        } catch (_: Exception) {
            return newSearchResponseList(emptyList(), false)
        }

        val items = parseCardGrid(doc)
        val hasNext = items.isNotEmpty() && hasNextPage(doc, currentPage)
        return newSearchResponseList(items, hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val targetUrl = normalizeSiteUrl(url) ?: return null
        val doc = try {
            app.get(targetUrl, headers = defaultHeaders).document
        } catch (_: Exception) {
            return null
        }

        val html = doc.html()
        val ldInfo = parseJsonLd(doc)

        val h1Title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        val displayTitle = h1Title ?: ldInfo.name ?: ogTitle ?: return null

        val posterUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { normalizeUrl(it, mainUrl) }
            ?: ldInfo.image?.let { normalizeUrl(it, mainUrl) }
            ?: doc.selectFirst("main img[src]:not([src^=data:])")?.attr("src")
                ?.let { normalizeUrl(it, mainUrl) }

        val backdropUrl = doc.selectFirst("img[src*=image.tmdb.org/t/p/]")?.attr("src")
            ?.let { normalizeUrl(it, mainUrl) }
            ?: posterUrl

        val rawDescription = ldInfo.description
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val yearText = ldInfo.datePublished
            ?: doc.selectFirst("h1")?.parent()?.parent()?.text()
            ?: doc.title()
        val parsedYear = Regex("""\b(19\d{2}|20\d{2})\b""").find(yearText)?.groupValues?.get(1)?.toIntOrNull()

        val ratingText = doc.selectFirst("#display-rating-score")?.text()
            ?.substringBefore("/")?.trim()
            ?: ldInfo.ratingValue
        val parsedScore = ratingText?.let { Score.from10(it) }

        val audioLanguages = doc.select("a[href^=/language/]").mapNotNull {
            it.text().trim().takeIf { txt -> txt.isNotEmpty() }
        }.distinct()

        val actors = doc.select("a[href^=/actor/]").mapNotNull {
            it.text().trim().takeIf { txt -> txt.isNotEmpty() }
        }.distinct()

        val tagList = buildList {
            ldInfo.breadcrumbCategory?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(audioLanguages)
        }.distinct()

        val fullPlot = buildString {
            if (!rawDescription.isNullOrBlank()) {
                append(rawDescription.trim())
            }
            if (!ldInfo.director.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Director: ").append(ldInfo.director)
            }
            if (actors.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("Cast: ").append(actors.joinToString(", "))
            }
            if (audioLanguages.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("Audio: ").append(audioLanguages.joinToString(", "))
            }
        }.ifBlank { null }

        val trailerUrl = doc.selectFirst("#trailer-iframe")?.attr("data-src")
            ?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }

        val recommendations = doc.select("a.movie-card")
            .mapNotNull { parseMovieCard(it) }
            .filter { it.url != targetUrl }
            .distinctBy { it.url }

        val isSeries = Regex("""\bconst\s+isSeries\s*=\s*(true|false)\b""")
            .find(html)?.groupValues?.get(1)?.equals("true", ignoreCase = true)
            ?: ldInfo.type.equals("TVSeries", ignoreCase = true)

        val isAnime = Regex("""\bconst\s+isAnime\s*=\s*(true|false)\b""")
            .find(html)?.groupValues?.get(1)?.equals("true", ignoreCase = true)
            ?: ldInfo.breadcrumbCategory.equals("Animation", ignoreCase = true)

        val movieWatchLink = Regex("""\bconst\s+movieWatchLink\s*=\s*"((?:\\.|[^"\\])*)"""")
            .find(html)?.groupValues?.get(1)?.let { unescapeJsString(it) }?.trim().orEmpty()

        val rawEpisodes = extractJsArrayObjects(html, "rawEpisodes")
        val rawLinks = extractJsArrayObjects(html, "rawLinks")

        if (!isSeries) {
            val movieSources = mutableListOf<SourceSpec>()
            if (movieWatchLink.isNotBlank() && movieWatchLink != "#") {
                movieSources.add(
                    SourceSpec(
                        url = movieWatchLink,
                        label = "Main Stream"
                    )
                )
            }
            rawLinks.forEach { entry ->
                val link = entry.link.trim()
                if (link.isBlank() || link == "#") return@forEach
                movieSources.add(
                    SourceSpec(
                        url = link,
                        label = entry.name.ifBlank { entry.quality.ifBlank { "Stream" } },
                        quality = entry.quality,
                        language = entry.language,
                        size = entry.size
                    )
                )
            }

            val payloadJson = mapper.writeValueAsString(
                LoadPayload(
                    pageUrl = targetUrl,
                    sources = movieSources.distinctBy { it.url }
                )
            )

            val movieType = if (isAnime) TvType.AnimeMovie else TvType.Movie
            return newMovieLoadResponse(displayTitle, targetUrl, movieType, payloadJson) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.plot = fullPlot
                this.year = parsedYear
                this.score = parsedScore
                this.tags = tagList.takeIf { it.isNotEmpty() }
                this.recommendations = recommendations
                trailerUrl?.let { addTrailer(listOf(it)) }
            }
        }

        // Episodic content (Web Series / Anime)
        val titleSeason = Regex("""\bS(?:eason)?\s*(\d+)\b""", RegexOption.IGNORE_CASE)
            .find(displayTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val buckets = linkedMapOf<Pair<Int, Int>, EpisodeBucket>()
        var sequentialFallback = 1

        fun addEpisodeSource(epTitleRaw: String, source: SourceSpec, allowUnnumbered: Boolean) {
            if (source.url.isBlank() || source.url == "#") return
            if (isBatchOrZipName(epTitleRaw)) return
            val parsed = parseSeasonEpisode(epTitleRaw, defaultSeason = titleSeason)
            val seasonNum = parsed?.first ?: titleSeason
            val epNum = parsed?.second ?: if (allowUnnumbered) sequentialFallback++ else return
            val key = seasonNum to epNum
            val existing = buckets[key]
            val cleanTitle = epTitleRaw.trim().ifBlank { "Episode $epNum" }
            if (existing == null) {
                buckets[key] = EpisodeBucket(
                    season = seasonNum,
                    episode = epNum,
                    title = cleanTitle,
                    sources = mutableListOf(source)
                )
            } else {
                if (existing.sources.none { it.url == source.url }) {
                    existing.sources.add(source)
                }
            }
        }

        rawEpisodes.forEach { ep ->
            addEpisodeSource(
                epTitleRaw = ep.name,
                source = SourceSpec(
                    url = ep.link.trim(),
                    label = ep.name.ifBlank { "Player" },
                    quality = ep.quality,
                    language = ep.language,
                    size = ep.size
                ),
                allowUnnumbered = true
            )
        }

        rawLinks.forEach { lnk ->
            addEpisodeSource(
                epTitleRaw = lnk.name,
                source = SourceSpec(
                    url = lnk.link.trim(),
                    label = lnk.name,
                    quality = lnk.quality,
                    language = lnk.language,
                    size = lnk.size
                ),
                allowUnnumbered = false
            )
        }

        val episodes = buckets.values
            .sortedWith(compareBy<EpisodeBucket> { it.season }.thenBy { it.episode })
            .map { bucket ->
                val epPayload = mapper.writeValueAsString(
                    LoadPayload(
                        pageUrl = targetUrl,
                        sources = bucket.sources.distinctBy { it.url }
                    )
                )
                newEpisode(epPayload) {
                    this.name = bucket.title
                    this.season = bucket.season
                    this.episode = bucket.episode
                    this.posterUrl = backdropUrl ?: posterUrl
                }
            }

        return if (isAnime) {
            newAnimeLoadResponse(displayTitle, targetUrl, TvType.Anime) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.plot = fullPlot
                this.year = parsedYear
                this.score = parsedScore
                this.tags = tagList.takeIf { it.isNotEmpty() }
                this.recommendations = recommendations
                trailerUrl?.let { addTrailer(listOf(it)) }
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            newTvSeriesLoadResponse(displayTitle, targetUrl, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.plot = fullPlot
                this.year = parsedYear
                this.score = parsedScore
                this.tags = tagList.takeIf { it.isNotEmpty() }
                this.recommendations = recommendations
                trailerUrl?.let { addTrailer(listOf(it)) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = try {
            mapper.readValue(data, LoadPayload::class.java)
        } catch (_: Exception) {
            val clean = data.trim()
            if (clean.isEmpty()) return false
            LoadPayload(
                pageUrl = mainUrl,
                sources = listOf(SourceSpec(url = clean, label = "Stream"))
            )
        }

        if (payload.sources.isEmpty()) return false

        val referer = payload.pageUrl.ifBlank { "$mainUrl/" }
        val seenPlayers = mutableSetOf<String>()
        val seenStreams = mutableSetOf<String>()
        val seenSubtitles = mutableSetOf<String>()
        var foundAny = false

        for (source in payload.sources) {
            val resolvedPlayerUrls = resolveToPlayerCandidates(source.url, referer)
            for (candidateUrl in resolvedPlayerUrls) {
                if (!seenPlayers.add(candidateUrl)) continue

                val extracted = extractFromCandidateUrl(
                    candidateUrl = candidateUrl,
                    source = source,
                    referer = referer,
                    seenStreams = seenStreams,
                    seenSubtitles = seenSubtitles,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                if (extracted) {
                    foundAny = true
                }
            }
        }

        return foundAny
    }

    private suspend fun resolveToPlayerCandidates(rawUrl: String, referer: String): List<String> {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed == "#") return emptyList()

        val absolute = normalizeUrl(trimmed, mainUrl) ?: return emptyList()
        val candidates = mutableListOf<String>()

        // Case 1: Already /player/<24-char-id> or jiofiles embed URL
        val jioMatch = Regex("""/([a-zA-Z0-9]{24})(?:$|/|\?)""").find(absolute)
        if (jioMatch != null && (
                absolute.contains("/player/", ignoreCase = true) ||
                    absolute.contains("jiofiles", ignoreCase = true) ||
                    absolute.contains("jiourl", ignoreCase = true) ||
                    absolute.contains("embed.", ignoreCase = true) ||
                    absolute.contains("player.", ignoreCase = true)
                )
        ) {
            candidates.add("$mainUrl/player/${jioMatch.groupValues[1]}")
            return candidates
        }

        // Case 2: /file/<base64_token> -> decode first segment to get 24-hex FILE_ID -> /player/<FILE_ID>
        val fileTokenMatch = Regex("""/file/([A-Za-z0-9_-]+)""").find(absolute)
        if (fileTokenMatch != null) {
            val token = fileTokenMatch.groupValues[1]
            val decoded = decodeBase64(token)
            val fileId = decoded.substringBefore("|").trim()
            if (Regex("""^[a-zA-Z0-9]{24}$""").matches(fileId)) {
                candidates.add("$mainUrl/player/$fileId")
            }

            // Fallback: fetch /file/<token> page and resolve /file/<FILE_ID>/download?token=<DL_TOKEN>&mode=stream
            try {
                val fileHtml = app.get(
                    absolute,
                    headers = defaultHeaders + ("Referer" to referer)
                ).text
                val jsFileId = Regex("""const\s+FILE_ID\s*=\s*"([a-zA-Z0-9]{24})"""")
                    .find(fileHtml)?.groupValues?.get(1)
                val dlToken = Regex("""const\s+DL_TOKEN\s*=\s*"([^"]+)"""")
                    .find(fileHtml)?.groupValues?.get(1)
                if (!jsFileId.isNullOrBlank()) {
                    candidates.add("$mainUrl/player/$jsFileId")
                }
                if (!jsFileId.isNullOrBlank() && !dlToken.isNullOrBlank()) {
                    val streamRedirectUrl =
                        "$mainUrl/file/${URLEncoder.encode(jsFileId, "UTF-8")}/download?token=${
                            URLEncoder.encode(dlToken, "UTF-8")
                        }&mode=stream"
                    candidates.add(streamRedirectUrl)
                }
            } catch (_: Exception) {
            }
            return candidates.distinct()
        }

        candidates.add(absolute)
        return candidates
    }

    private suspend fun extractFromCandidateUrl(
        candidateUrl: String,
        source: SourceSpec,
        referer: String,
        seenStreams: MutableSet<String>,
        seenSubtitles: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Handle MovieNestBD /player/<id> wrapper page
        if (candidateUrl.contains("/player/", ignoreCase = true)) {
            val playerHtml = try {
                app.get(
                    candidateUrl,
                    headers = defaultHeaders + ("Referer" to referer)
                ).text
            } catch (_: Exception) {
                return false
            }

            val playerDoc = org.jsoup.Jsoup.parse(playerHtml, candidateUrl)
            val iframeSrcs = buildList {
                playerDoc.select("iframe[src]").forEach { el ->
                    normalizeUrl(el.attr("src"), candidateUrl)?.let { add(it) }
                }
                Regex("""data-url=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .findAll(playerHtml)
                    .forEach { m ->
                        normalizeUrl(m.groupValues[1], candidateUrl)?.let { add(it) }
                    }
            }.distinct()

            var found = false
            for (embedUrl in iframeSrcs) {
                if (extractEmbedOrXcloud(
                        embedUrl = embedUrl,
                        source = source,
                        referer = candidateUrl,
                        seenStreams = seenStreams,
                        seenSubtitles = seenSubtitles,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                ) {
                    found = true
                }
            }
            return found
        }

        return extractEmbedOrXcloud(
            embedUrl = candidateUrl,
            source = source,
            referer = referer,
            seenStreams = seenStreams,
            seenSubtitles = seenSubtitles,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private suspend fun extractEmbedOrXcloud(
        embedUrl: String,
        source: SourceSpec,
        referer: String,
        seenStreams: MutableSet<String>,
        seenSubtitles: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Resolve redirects to reach final host (e.g. mn.xcloud.asia -> xcloud1.space -> stream.xcloud1.sbs)
        val response = try {
            app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer
                ),
                allowRedirects = true
            )
        } catch (_: Exception) {
            return loadExtractor(embedUrl, referer, subtitleCallback, callback)
        }

        val finalUrl = response.url.ifBlank { embedUrl }
        val body = response.text

        if (body.contains("__XCLOUD_PLAYER__")) {
            return extractXcloudPlayer(
                pageUrl = finalUrl,
                html = body,
                source = source,
                seenStreams = seenStreams,
                seenSubtitles = seenSubtitles,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        }

        // If it's a direct media file URL
        val ct = (response.headers["Content-Type"] ?: response.headers["content-type"]).orEmpty()
        if (ct.startsWith("video/", ignoreCase = true) ||
            ct.contains("mpegurl", ignoreCase = true) ||
            finalUrl.contains(".m3u8", ignoreCase = true) ||
            finalUrl.contains(".mp4", ignoreCase = true) ||
            finalUrl.contains(".mkv", ignoreCase = true)
        ) {
            if (seenStreams.add(finalUrl)) {
                val isM3u8 = ct.contains("mpegurl", ignoreCase = true) ||
                    finalUrl.contains(".m3u8", ignoreCase = true)
                callback(
                    newExtractorLink(
                        source = name,
                        name = buildSourceLabel(source, "Direct"),
                        url = finalUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = parseQualityFromText(source.quality)
                    }
                )
                return true
            }
        }

        return loadExtractor(finalUrl, referer, subtitleCallback, callback)
    }

    private suspend fun extractXcloudPlayer(
        pageUrl: String,
        html: String,
        source: SourceSpec,
        seenStreams: MutableSet<String>,
        seenSubtitles: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cfgJson = extractAssignedJsonObject(html, "window.__XCLOUD_PLAYER__") ?: return false
        val cfg = try {
            mapper.readTree(cfgJson)
        } catch (_: Exception) {
            return false
        }

        val origin = extractOrigin(pageUrl) ?: "https://stream.xcloud1.sbs"
        var emitted = false

        // 1. Subtitles from rawSubs -> https://sub.xcloud.icu/<id>
        val rawSubs = cfg.path("rawSubs")
        if (rawSubs.isArray) {
            for (subNode in rawSubs) {
                val subId = subNode.path("id").asText("").ifBlank {
                    subNode.path("tgid").asText("")
                }.trim()
                if (subId.isEmpty()) continue
                val subUrl = "https://sub.xcloud.icu/$subId"
                if (!seenSubtitles.add(subUrl)) continue
                val langLabel = subNode.path("lang").asText("").ifBlank {
                    subNode.path("lang_code").asText("Subtitle")
                }.trim()
                subtitleCallback(
                    newSubtitleFile(
                        lang = langLabel,
                        url = subUrl
                    )
                )
            }
        }

        // 2. Direct hlsUrl if already populated
        val directHls = cfg.path("hlsUrl").asText("").takeIf {
            it.isNotBlank() && !it.equals("null", ignoreCase = true)
        }
        if (directHls != null) {
            val resolvedHls = normalizeUrl(directHls, origin)
            if (resolvedHls != null && seenStreams.add(resolvedHls)) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = buildSourceLabel(source, "HLS"),
                        url = resolvedHls,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$origin/"
                        this.quality = parseQualityFromText(source.quality)
                    }
                )
                emitted = true
            }
        }

        // 3. Worker pool (/api/encode) -> fast multi-quality MP4 or HLS streams
        val encodedDriveId = cfg.path("encodedDriveId").asText("").takeIf {
            it.isNotBlank() && !it.equals("null", ignoreCase = true)
        }
        val isSafeFilename = cfg.path("isSafeFilename").asBoolean(true)
        val workerPools = cfg.path("workerPools")
        if (isSafeFilename && encodedDriveId != null && workerPools.isArray && workerPools.size() > 0) {
            val payloadMap = mutableMapOf<String, Any>("driveid" to encodedDriveId)
            val audiosNode = cfg.path("audios")
            if (audiosNode.isArray && audiosNode.size() > 0) {
                payloadMap["audios"] = audiosNode
            }
            val jsonBody = mapper.writeValueAsString(payloadMap)
            val mediaType = "application/json; charset=utf-8".toMediaType()

            for (i in 0 until minOf(workerPools.size(), 3)) {
                val workerBase = workerPools[i].asText("").trim().trimEnd('/')
                if (!workerBase.startsWith("http", ignoreCase = true)) continue
                val encodeRes = try {
                    app.post(
                        "$workerBase/api/encode",
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Origin" to origin,
                            "Referer" to "$origin/"
                        ),
                        requestBody = jsonBody.toRequestBody(mediaType)
                    ).text
                } catch (_: Exception) {
                    continue
                }

                val root = try {
                    mapper.readTree(encodeRes)
                } catch (_: Exception) {
                    continue
                }
                if (!root.path("status").asText("").equals("success", ignoreCase = true)) {
                    continue
                }

                val workerHls = root.path("hls").asText("").takeIf {
                    it.isNotBlank() && !it.equals("null", ignoreCase = true)
                }
                if (workerHls != null && seenStreams.add(workerHls)) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = buildSourceLabel(source, "Stream HLS"),
                            url = workerHls,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$origin/"
                            this.quality = parseQualityFromText(source.quality)
                        }
                    )
                    emitted = true
                }

                val videoObj = root.path("video")
                if (videoObj.isObject) {
                    val fields = videoObj.fields()
                    while (fields.hasNext()) {
                        val (qKey, qVal) = fields.next()
                        val streamUrl = qVal.asText("").trim()
                        if (streamUrl.startsWith("http", ignoreCase = true) && seenStreams.add(streamUrl)) {
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = buildSourceLabel(source, "Fast $qKey"),
                                    url = streamUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "$origin/"
                                    this.quality = parseQualityFromText(qKey)
                                }
                            )
                            emitted = true
                        }
                    }
                }
                if (emitted) break
            }
        }

        // 4. Primary & Wasabi R2 mirror redirect endpoints (must use GET with allowRedirects = false)
        val redirectSpecs = listOf(
            "Cloud Primary" to cfg.path("primaryUrl").asText(""),
            "Cloud Backup" to cfg.path("wasabiUrl").asText("")
        )
        for ((serverTag, rawRedir) in redirectSpecs) {
            val redirPath = rawRedir.trim()
            if (redirPath.isBlank() || redirPath.equals("null", ignoreCase = true)) continue
            val redirUrl = normalizeUrl(redirPath, origin) ?: continue
            val resolvedR2 = resolveRedirectLocation(redirUrl, pageUrl) ?: continue
            if (seenStreams.add(resolvedR2)) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = buildSourceLabel(source, serverTag),
                        url = resolvedR2,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = parseQualityFromText(source.quality.ifBlank { source.label })
                    }
                )
                emitted = true
            }
        }

        return emitted
    }

    private suspend fun resolveRedirectLocation(url: String, referer: String): String? {
        return try {
            val res = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer
                ),
                allowRedirects = false
            )
            val loc = (res.headers["Location"] ?: res.headers["location"])?.trim()
            if (!loc.isNullOrBlank()) {
                normalizeUrl(loc, extractOrigin(url) ?: url)
            } else if (res.url.isNotBlank() && res.url != url) {
                res.url
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCardGrid(doc: Document): List<SearchResponse> {
        return doc.select("a.movie-card")
            .mapNotNull { parseMovieCard(it) }
            .distinctBy { it.url }
    }

    private fun parseMovieCard(card: Element): SearchResponse? {
        val href = card.attr("href").trim()
        val itemUrl = normalizeSiteUrl(href) ?: return null

        val h3 = card.selectFirst("h3")
        val yearText = h3?.selectFirst("span")?.text()?.trim().orEmpty()
        val yearVal = Regex("""\b(19\d{2}|20\d{2})\b""").find(yearText)?.groupValues?.get(1)?.toIntOrNull()

        val titleFromH3 = h3?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: h3?.text()?.replace(yearText, "")?.trim()?.takeIf { it.isNotBlank() }
        val img = card.selectFirst("img")
        val title = titleFromH3 ?: img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() } ?: return null

        val poster = img?.let {
            val src = it.attr("src").trim()
            if (src.isNotBlank() && !src.startsWith("data:", ignoreCase = true)) {
                normalizeUrl(src, mainUrl)
            } else {
                null
            }
        }

        val badgesText = card.select("div.absolute").joinToString(" ") { it.text() }
        val isSeriesCard = badgesText.contains("SERIES", ignoreCase = true)
        val isAnimeCard = badgesText.contains("ANIME", ignoreCase = true) ||
            itemUrl.contains("anime", ignoreCase = true)

        return when {
            isAnimeCard -> newAnimeSearchResponse(title, itemUrl, TvType.Anime) {
                this.posterUrl = poster
                this.year = yearVal
            }
            isSeriesCard -> newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = yearVal
            }
            else -> newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                this.posterUrl = poster
                this.year = yearVal
            }
        }
    }

    private fun hasNextPage(doc: Document, currentPage: Int): Boolean {
        val nextPageStr = "page=${currentPage + 1}"
        if (doc.selectFirst("a[href*=$nextPageStr]") != null) return true
        return doc.select("a").any { el ->
            val txt = el.text().trim()
            txt.equals("Next", ignoreCase = true) || txt == "»" || txt == "›"
        }
    }

    private data class JsonLdInfo(
        val type: String? = null,
        val name: String? = null,
        val description: String? = null,
        val image: String? = null,
        val datePublished: String? = null,
        val ratingValue: String? = null,
        val director: String? = null,
        val breadcrumbCategory: String? = null
    )

    private fun parseJsonLd(doc: Document): JsonLdInfo {
        var type: String? = null
        var name: String? = null
        var description: String? = null
        var image: String? = null
        var datePublished: String? = null
        var ratingValue: String? = null
        var director: String? = null
        var breadcrumbCategory: String? = null

        for (script in doc.select("script[type=application/ld+json]")) {
            val raw = script.data().trim()
            if (raw.isEmpty()) continue
            val root = try {
                mapper.readTree(raw)
            } catch (_: Exception) {
                continue
            }
            val ldType = root.path("@type").asText("").trim()
            if (ldType.equals("Movie", ignoreCase = true) ||
                ldType.equals("TVSeries", ignoreCase = true) ||
                ldType.equals("TVSeason", ignoreCase = true)
            ) {
                type = ldType
                name = root.path("name").textOrNull()
                description = root.path("description").textOrNull()
                image = root.path("image").textOrNull()
                datePublished = root.path("datePublished").textOrNull()
                ratingValue = root.path("aggregateRating").path("ratingValue").textOrNull()
                director = root.path("director").path("name").textOrNull()
            } else if (ldType.equals("BreadcrumbList", ignoreCase = true)) {
                val items = root.path("itemListElement")
                if (items.isArray && items.size() >= 2) {
                    breadcrumbCategory = items[1].path("name").textOrNull()
                }
            }
        }

        return JsonLdInfo(
            type = type,
            name = name,
            description = description,
            image = image,
            datePublished = datePublished,
            ratingValue = ratingValue,
            director = director,
            breadcrumbCategory = breadcrumbCategory
        )
    }

    private fun extractJsArrayObjects(html: String, varName: String): List<RawMediaLink> {
        val declRegex = Regex("""\b(?:let|const|var)\s+$varName\s*=\s*\[""")
        val declMatch = declRegex.find(html) ?: return emptyList()
        val startBracket = declMatch.range.last
        var depth = 0
        var inString = false
        var escaped = false
        var endBracket = -1

        for (i in startBracket until html.length) {
            val ch = html[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        endBracket = i
                        break
                    }
                }
            }
        }

        if (endBracket <= startBracket) return emptyList()
        val arrayBody = html.substring(startBracket + 1, endBracket)
        val results = mutableListOf<RawMediaLink>()
        val objBlocks = splitTopLevelObjects(arrayBody)

        for (block in objBlocks) {
            val props = mutableMapOf<String, String>()
            val propRegex = Regex("""(\w+)\s*:\s*"((?:\\.|[^"\\])*)"""")
            propRegex.findAll(block).forEach { m ->
                props[m.groupValues[1]] = unescapeJsString(m.groupValues[2])
            }
            val link = props["link"]?.trim().orEmpty()
            if (link.isEmpty()) continue
            results.add(
                RawMediaLink(
                    name = props["name"]?.trim().orEmpty(),
                    link = link,
                    quality = props["quality"]?.trim().orEmpty(),
                    language = props["language"]?.trim().orEmpty(),
                    size = props["size"]?.trim().orEmpty()
                )
            )
        }
        return results
    }

    private fun splitTopLevelObjects(arrayBody: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var objStart = -1
        var inString = false
        var escaped = false

        for (i in arrayBody.indices) {
            val ch = arrayBody[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) objStart = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        objects.add(arrayBody.substring(objStart, i + 1))
                        objStart = -1
                    }
                }
            }
        }
        return objects
    }

    private fun extractAssignedJsonObject(html: String, assignmentTarget: String): String? {
        val idx = html.indexOf(assignmentTarget)
        if (idx < 0) return null
        val startBrace = html.indexOf('{', idx)
        if (startBrace < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (i in startBrace until html.length) {
            val ch = html[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return html.substring(startBrace, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun isBatchOrZipName(name: String): Boolean {
        val lower = name.trim().lowercase()
        if (lower.isEmpty()) return false
        if (listOf("zip", "rar", "pack", "batch", "complete", "all episode", "all ep", "full season", "whole season")
                .any { lower.contains(it) }
        ) {
            return true
        }
        val rangeRegex = Regex(
            """(?:^|[\s\[(_\-])(?:s(?:eason)?\s*\d+[\s._\-]*)?e(?:p(?:isode)?\.?)?\s*(\d+)\s*(?:-|–|—|~|/|&|\+|and|,|to|till|until)\s*(?:e(?:p(?:isode)?\.?)?\s*)?(\d+)(\w*)""",
            RegexOption.IGNORE_CASE
        )
        for (m in rangeRegex.findAll(name)) {
            val ep2 = m.groupValues[2].toIntOrNull() ?: continue
            val suffix = m.groupValues[3].lowercase()
            if (suffix.startsWith("p") || suffix.startsWith("k") || suffix.startsWith("fps") || suffix.startsWith("bit")) {
                continue
            }
            if (ep2 in listOf(360, 480, 540, 720, 1080, 2160, 4320)) continue
            if (ep2 in 1900..2099 && !m.value.lowercase().contains("e$ep2")) continue
            return true
        }
        return false
    }

    private fun parseSeasonEpisode(rawName: String, defaultSeason: Int): Pair<Int, Int>? {
        val sxeMatch = Regex(
            """s(?:eason)?\s*(\d+)[\s._\-]*e(?:p(?:isode)?\.?)?\s*(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(rawName)
        if (sxeMatch != null) {
            val s = sxeMatch.groupValues[1].toIntOrNull() ?: defaultSeason
            val e = sxeMatch.groupValues[2].toIntOrNull() ?: return null
            return s.coerceAtLeast(1) to e.coerceAtLeast(1)
        }

        val epMatch = Regex(
            """(?:^|[\s\[(_\-])e(?:p(?:isode)?\.?)?\s*(\d+)(?:\b|[^\d])""",
            RegexOption.IGNORE_CASE
        ).find(rawName)
            ?: Regex("""^(\d+)$""").find(rawName.trim())

        val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return defaultSeason.coerceAtLeast(1) to epNum.coerceAtLeast(1)
    }

    private fun buildSourceLabel(source: SourceSpec, serverTag: String): String {
        val parts = mutableListOf(name, serverTag)
        if (source.quality.isNotBlank() && !serverTag.contains(source.quality, ignoreCase = true)) {
            parts.add(source.quality)
        }
        if (source.language.isNotBlank()) {
            parts.add(source.language)
        }
        if (source.size.isNotBlank()) {
            parts.add(source.size)
        }
        return parts.joinToString(" | ")
    }

    private fun parseQualityFromText(qualityText: String): Int {
        val resMatch = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(qualityText)
        return if (resMatch != null) {
            getQualityFromName("${resMatch.groupValues[1]}p")
        } else {
            getQualityFromName(qualityText)
        }
    }

    private fun normalizeSiteUrl(raw: String?): String? {
        val u = normalizeUrl(raw, mainUrl) ?: return null
        return u
            .replace("https://movienestbd.page", mainUrl, ignoreCase = true)
            .replace("https://movienestbd.click", mainUrl, ignoreCase = true)
            .replace("http://movienestbd.best", mainUrl, ignoreCase = true)
    }

    private fun normalizeUrl(raw: String?, baseOrigin: String): String? {
        val trimmed = raw?.trim()
            ?.replace("&#038;", "&")
            ?.replace("&amp;", "&")
            ?.takeIf { it.isNotBlank() } ?: return null
        if (trimmed == "#" || trimmed.startsWith("javascript:", ignoreCase = true)) return null
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "${baseOrigin.trimEnd('/')}$trimmed"
            else -> "${baseOrigin.trimEnd('/')}/$trimmed"
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

    private fun unescapeJsString(raw: String): String {
        return try {
            mapper.readTree("\"$raw\"").asText(raw)
        } catch (_: Exception) {
            raw.replace("\\/", "/").replace("\\\"", "\"")
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

    private fun JsonNode?.textOrNull(): String? {
        if (this == null || this.isMissingNode || this.isNull) return null
        val text = this.asText("").trim()
        return if (text.isEmpty() || text.equals("null", ignoreCase = true)) null else text
    }
}
