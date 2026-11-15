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
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

class Just4AnimeProvider : MainAPI() {
    override var mainUrl = "https://just4anime.online"
    override var name = "Just4Anime"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val apiBase = "https://api.just4anime.online/api"
    private val mapper = jacksonObjectMapper()
    private val pageSize = 20

    private val apiHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "Accept" to "application/json, text/plain, */*"
    )

    private val webHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Accept" to "application/json, text/plain, */*"
    )

    private val defaultServerFallbackOrder = listOf(
        "jin", "rin", "levi", "xan", "sen", "kai", "mai", "sai", "zeke", "rei"
    )

    private val validProviderAnimeIdRegex = Regex("""^[a-zA-Z0-9._-]+(?:/[a-zA-Z0-9._-]+)*$""")
    private val animeIdFromUrlRegex = Regex("""/(?:anime|watch)/(\d+)""")
    private val digitsOnlyRegex = Regex("""^(\d+)$""")

    override val mainPage = mainPageOf(
        "feed:trending" to "Trending Now",
        "feed:popular" to "Popular",
        "feed:top-airing" to "Top Airing",
        "feed:top-rated" to "Top Rated",
        "feed:most-popular" to "Most Popular",
        "feed:most-favorite" to "Most Favorite",
        "adv:format=MOVIE&sort=[\"POPULARITY_DESC\"]" to "Anime Movies",
        "adv:format=OVA&sort=[\"POPULARITY_DESC\"]" to "OVAs",
        "adv:genres=[\"Action\"]&sort=[\"POPULARITY_DESC\"]" to "Action",
        "adv:genres=[\"Adventure\"]&sort=[\"POPULARITY_DESC\"]" to "Adventure",
        "adv:genres=[\"Comedy\"]&sort=[\"POPULARITY_DESC\"]" to "Comedy",
        "adv:genres=[\"Drama\"]&sort=[\"POPULARITY_DESC\"]" to "Drama",
        "adv:genres=[\"Fantasy\"]&sort=[\"POPULARITY_DESC\"]" to "Fantasy",
        "adv:genres=[\"Romance\"]&sort=[\"POPULARITY_DESC\"]" to "Romance",
        "adv:genres=[\"Sci-Fi\"]&sort=[\"POPULARITY_DESC\"]" to "Sci-Fi"
    )

    private data class ServerCandidate(
        val code: String,
        val animeId: String?,
        val types: List<String>,
        val hasEpisode: Boolean,
        val totalEpisodes: Int,
        val healthScore: Double
    )

    private data class WatchTarget(
        val animeId: String,
        val episode: Int,
        val requestedType: String?
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val currentPage = page.coerceAtLeast(1)
        val url = buildCatalogUrl(request.data, currentPage) ?: return newHomePageResponse(emptyList(), false)
        val root = getJson(url, apiHeaders) ?: return newHomePageResponse(emptyList(), false)
        if (!root.path("success").asBoolean(true)) {
            return newHomePageResponse(emptyList(), false)
        }
        val dataNode = root.path("data")
        val items = parseSearchResults(dataNode.path("results"))
        val hasNext = items.isNotEmpty() && dataNode.path("hasNextPage").asBoolean(false)

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

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val clean = query.trim()
        if (clean.isEmpty()) return emptyList()
        val url = "$apiBase/v1/meta/anilist/search?query=${urlEncode(clean)}&page=1&perPage=12"
        val root = getJson(url, apiHeaders) ?: return emptyList()
        if (!root.path("success").asBoolean(true)) return emptyList()
        return parseSearchResults(root.path("data").path("results"))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val clean = query.trim()
        if (clean.isEmpty()) return newSearchResponseList(emptyList(), false)
        val currentPage = page.coerceAtLeast(1)
        val url = "$apiBase/v1/meta/anilist/search?query=${urlEncode(clean)}&page=$currentPage&perPage=$pageSize"
        val root = getJson(url, apiHeaders) ?: return newSearchResponseList(emptyList(), false)
        if (!root.path("success").asBoolean(true)) {
            return newSearchResponseList(emptyList(), false)
        }
        val dataNode = root.path("data")
        val items = parseSearchResults(dataNode.path("results"))
        val hasNext = items.isNotEmpty() && dataNode.path("hasNextPage").asBoolean(false)
        return newSearchResponseList(items, hasNext = hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val animeId = extractAnimeId(url) ?: return null
        val canonicalUrl = "$mainUrl/anime/$animeId"

        val infoRoot = getJson("$apiBase/v1/meta/anilist/info/$animeId", apiHeaders) ?: return null
        if (!infoRoot.path("success").asBoolean(false)) return null

        val infoData = infoRoot.path("data")
        val anilist = infoData.path("anilist")
        if (anilist.isMissingNode || anilist.isNull) return null

        val episodesRoot = getJson("$mainUrl/api/episodes/$animeId", webHeaders)
        val episodesData = episodesRoot?.takeIf { it.path("success").asBoolean(false) }?.path("data")

        val title = pickTitle(anilist.path("title"))
            ?: episodesData?.path("title")?.textOrNull()
            ?: "Anime #$animeId"

        val tmdbImages = episodesData?.path("images")
        val poster = anilist.path("image").textOrNull()
            ?: findTmdbImage(tmdbImages, "Poster")
            ?: infoData.path("kuroiru").path("image").textOrNull()
        val backdrop = anilist.path("cover").textOrNull()
            ?: findTmdbImage(tmdbImages, "Banner")
            ?: findTmdbImage(tmdbImages, "Fanart")
            ?: poster

        val rawDesc = anilist.path("description").textOrNull()
            ?: episodesData?.path("description")?.textOrNull()
            ?: ""
        val plotText = cleanHtml(rawDesc)

        val releaseYear = anilist.path("releaseDate").asInt(0).takeIf { it > 1900 }
            ?: anilist.path("startDate").path("year").asInt(0).takeIf { it > 1900 }
            ?: episodesData?.path("year")?.asInt(0)?.takeIf { it > 1900 }

        val rawRating = anilist.path("rating").asDouble(0.0)
        val parsedScore = when {
            rawRating > 10.0 -> Score.from10(rawRating / 10.0)
            rawRating > 0.0 -> Score.from10(rawRating)
            else -> null
        }

        val statusRaw = anilist.path("status").textOrNull()
            ?: episodesData?.path("status")?.textOrNull()
            ?: ""
        val resolvedStatus = when {
            statusRaw.equals("Completed", ignoreCase = true) ||
                statusRaw.equals("Finished", ignoreCase = true) -> ShowStatus.Completed
            statusRaw.equals("Ongoing", ignoreCase = true) ||
                statusRaw.equals("Releasing", ignoreCase = true) ||
                statusRaw.equals("Currently Airing", ignoreCase = true) -> ShowStatus.Ongoing
            else -> null
        }

        val tagList = mutableListOf<String>()
        anilist.path("genres").forEach { g ->
            g.textOrNull()?.let { tagList.add(it) }
        }
        anilist.path("tags").forEach { t ->
            if (!t.path("isGeneralSpoiler").asBoolean(false) &&
                !t.path("isMediaSpoiler").asBoolean(false) &&
                !t.path("isAdult").asBoolean(false)
            ) {
                t.textOrNull()?.let { tagList.add(it) }
                t.path("name").textOrNull()?.let { tagList.add(it) }
            }
        }
        val finalTags = tagList.distinct().take(15).takeIf { it.isNotEmpty() }

        val trailerNode = anilist.path("trailer")
        val trailerSite = trailerNode.path("site").textOrNull()
        val trailerId = trailerNode.path("id").textOrNull()
        val trailerUrl = if (trailerSite.equals("youtube", ignoreCase = true) && trailerId != null) {
            "https://www.youtube.com/watch?v=$trailerId"
        } else {
            null
        }

        val recommendations = parseRecommendations(anilist.path("recommendations"))
        val formatRaw = anilist.path("type").textOrNull()
            ?: episodesData?.path("format")?.textOrNull()
            ?: "TV"
        val tvType = resolveTvType(formatRaw)

        // Inspect server availability for sub vs dub support and max episode count
        val servers = fetchAvailabilityServers(animeId)
        val hasSubServer = servers.isEmpty() || servers.any { srv ->
            srv.types.any { it.equals("sub", ignoreCase = true) || it.equals("h-sub", ignoreCase = true) || it.equals("embed", ignoreCase = true) }
        }
        val hasDubServer = servers.any { srv ->
            srv.types.any { it.equals("dub", ignoreCase = true) }
        }
        val maxSubServerEps = servers.filter { srv ->
            srv.types.any { it.equals("sub", ignoreCase = true) || it.equals("h-sub", ignoreCase = true) }
        }.maxOfOrNull { it.totalEpisodes } ?: 0
        val maxDubServerEps = servers.filter { srv ->
            srv.types.any { it.equals("dub", ignoreCase = true) }
        }.maxOfOrNull { it.totalEpisodes } ?: 0

        val rawEpArray = episodesData?.path("episodes")
        val rawEpNodes = mutableListOf<JsonNode>()
        if (rawEpArray != null && rawEpArray.isArray) {
            rawEpArray.forEach { rawEpNodes.add(it) }
        }

        val filteredEpNodes = filterMainSeasonEpisodes(rawEpNodes)
        val reportedCurrent = maxOf(
            anilist.path("currentEpisode").asInt(0),
            episodesData?.path("currentEpisode")?.asInt(0) ?: 0
        )
        val reportedTotal = maxOf(
            anilist.path("totalEpisodes").asInt(0),
            episodesData?.path("totalEpisodes")?.asInt(0) ?: 0,
            episodesData?.path("anilistEpisodeCount")?.asInt(0) ?: 0
        )

        val fallbackTotal = maxOf(
            filteredEpNodes.size,
            reportedCurrent,
            maxSubServerEps,
            if (reportedCurrent > 0) reportedCurrent else reportedTotal
        ).coerceAtLeast(if (tvType == TvType.AnimeMovie) 1 else 0)

        val effectiveCount = if (fallbackTotal > 0) fallbackTotal else 1

        if (tvType == TvType.AnimeMovie && effectiveCount <= 1) {
            val movieData = "$mainUrl/watch/$animeId?ep=1"
            return newMovieLoadResponse(title, canonicalUrl, TvType.AnimeMovie, movieData) {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                plot = plotText
                year = releaseYear
                score = parsedScore
                tags = finalTags
                this.recommendations = recommendations
                trailerUrl?.let { addTrailer(listOf(it)) }
            }
        }

        val epMetaByNum = LinkedHashMap<Int, JsonNode>()
        for (epNode in filteredEpNodes) {
            val num = epNode.path("number").asInt(0)
            if (num > 0 && !epMetaByNum.containsKey(num)) {
                epMetaByNum[num] = epNode
            }
        }

        val subTargetCount = maxOf(epMetaByNum.keys.maxOrNull() ?: 0, maxSubServerEps, reportedCurrent, effectiveCount)
        val dubTargetCount = when {
            maxDubServerEps > 0 -> maxDubServerEps
            hasDubServer -> subTargetCount
            else -> 0
        }

        val subEpisodes = if (hasSubServer) {
            buildEpisodeList(animeId, "sub", subTargetCount, epMetaByNum, poster)
        } else {
            emptyList()
        }
        val dubEpisodes = if (hasDubServer && dubTargetCount > 0) {
            buildEpisodeList(animeId, "dub", dubTargetCount, epMetaByNum, poster)
        } else {
            emptyList()
        }

        return newAnimeLoadResponse(title, canonicalUrl, tvType) {
            posterUrl = poster
            backgroundPosterUrl = backdrop
            plot = plotText
            year = releaseYear
            score = parsedScore
            tags = finalTags
            showStatus = resolvedStatus
            this.recommendations = recommendations
            trailerUrl?.let { addTrailer(listOf(it)) }
            if (subEpisodes.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, subEpisodes)
            }
            if (dubEpisodes.isNotEmpty()) {
                addEpisodes(DubStatus.Dubbed, dubEpisodes)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val target = parseWatchTarget(data) ?: return false
        val animeId = target.animeId
        val epNum = target.episode

        val servers = fetchAvailabilityServers(animeId)
        val priorityOrder = fetchServerPriority()

        val desiredSourceTypes = when (target.requestedType) {
            "dub" -> listOf("dub")
            "sub" -> listOf("sub", "hsub", "embed")
            else -> listOf("sub", "hsub", "dub", "embed")
        }

        val seenSubUrls = HashSet<String>()
        val seenStreamUrls = HashSet<String>()
        var foundAny = false
        var workingMirrors = 0
        val maxWorkingMirrors = 5

        // Build ordered (providerCode, providerAnimeId, queryType) attempts
        val attempts = mutableListOf<Triple<String, String?, String>>()
        if (servers.isNotEmpty()) {
            val sortedServers = servers.sortedWith(
                compareBy<ServerCandidate>(
                    { !it.hasEpisode },
                    { srv ->
                        val idx = priorityOrder.indexOf(srv.code.lowercase())
                        if (idx >= 0) idx else Int.MAX_VALUE
                    },
                    { -it.healthScore }
                )
            )
            for (srv in sortedServers) {
                val mappedTypes = srv.types.mapNotNull { normalizeAvailabilityType(it) }.distinct()
                for (reqType in desiredSourceTypes) {
                    if (reqType in mappedTypes) {
                        val apiType = if (reqType == "embed") "sub" else reqType
                        attempts.add(Triple(srv.code, srv.animeId, apiType))
                    }
                }
            }
        }

        if (attempts.isEmpty()) {
            val fallbackTypes = if (target.requestedType == "dub") listOf("dub") else listOf("sub", "hsub")
            for (code in defaultServerFallbackOrder) {
                for (t in fallbackTypes) {
                    attempts.add(Triple(code, null, t))
                }
            }
        }

        val triedPairs = HashSet<Pair<String, String>>()
        for ((providerCode, providerAnimeId, apiType) in attempts) {
            if (workingMirrors >= maxWorkingMirrors) break
            if (!triedPairs.add(providerCode to apiType)) continue

            val urlBuilder = StringBuilder("$apiBase/v1/meta/sources/${urlEncode(animeId)}")
                .append("?provider=").append(urlEncode(providerCode))
                .append("&num=").append(epNum)
                .append("&type=").append(urlEncode(apiType))

            val cleanProviderId = providerAnimeId?.trim()
            if (!cleanProviderId.isNullOrEmpty() && validProviderAnimeIdRegex.matches(cleanProviderId)) {
                urlBuilder.append("&providerAnimeId=").append(urlEncode(cleanProviderId))
            }

            val root = getJson(urlBuilder.toString(), apiHeaders) ?: continue
            if (!root.path("success").asBoolean(false)) continue
            val resData = root.path("data")
            if (resData.isMissingNode || resData.isNull) continue

            // Collect subtitles
            val subsNode = resData.path("subtitles")
            if (subsNode.isArray) {
                for (sub in subsNode) {
                    val subUrl = sub.path("url").textOrNull() ?: continue
                    if (!subUrl.startsWith("http", ignoreCase = true) || !seenSubUrls.add(subUrl)) continue
                    val subLabel = sub.path("label").textOrNull()
                        ?: sub.path("lang").textOrNull()
                        ?: sub.path("language").textOrNull()
                        ?: "English"
                    subtitleCallback(
                        newSubtitleFile(
                            lang = subLabel,
                            url = subUrl
                        )
                    )
                }
            }

            var mirrorEmitted = false
            val resType = resData.path("type").textOrNull() ?: apiType
            val isDubTrack = resData.path("isDub").asBoolean(false) ||
                apiType.equals("dub", ignoreCase = true)
            val audioTag = if (isDubTrack) "DUB" else "SUB"

            // Handle embed/iframe responses (e.g., server "rei" -> flixcloud.cc)
            val iframeNode = resData.path("iframe")
            if (iframeNode.isArray && iframeNode.size() > 0) {
                for (ifr in iframeNode) {
                    val embedUrl = ifr.path("url").textOrNull() ?: continue
                    if (!embedUrl.startsWith("http", ignoreCase = true) || !seenStreamUrls.add(embedUrl)) continue
                    val extracted = try {
                        loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
                    } catch (_: Exception) {
                        false
                    }
                    if (extracted) {
                        mirrorEmitted = true
                    }
                }
            }

            // Handle direct/proxied sources (m3u8 / video)
            val sourcesNode = resData.path("sources")
            if (sourcesNode.isArray && sourcesNode.size() > 0) {
                for (src in sourcesNode) {
                    val streamUrl = src.path("url").textOrNull() ?: continue
                    if (!streamUrl.startsWith("http", ignoreCase = true) || !seenStreamUrls.add(streamUrl)) continue

                    if (resType.equals("embed", ignoreCase = true)) {
                        val extracted = try {
                            loadExtractor(streamUrl, "$mainUrl/", subtitleCallback, callback)
                        } catch (_: Exception) {
                            false
                        }
                        if (extracted) {
                            mirrorEmitted = true
                        }
                        continue
                    }

                    val qualityLabel = src.path("quality").textOrNull() ?: "auto"
                    val isM3U8 = src.path("isM3U8").asBoolean(false) ||
                        streamUrl.contains(".m3u8", ignoreCase = true)
                    val proxied = src.path("proxied").asBoolean(false)
                    val srcReferer = src.path("headers").path("referer").textOrNull()
                    val resolvedReferer = if (proxied || streamUrl.contains("cors.just4anime.online", ignoreCase = true)) {
                        "$mainUrl/"
                    } else {
                        srcReferer ?: "$mainUrl/"
                    }

                    val linkTitle = buildString {
                        append(name)
                        append(" - ")
                        append(providerCode.uppercase())
                        append(" ")
                        append(audioTag)
                        if (!qualityLabel.equals("auto", ignoreCase = true) && !qualityLabel.equals("default", ignoreCase = true)) {
                            append(" (").append(qualityLabel).append(")")
                        }
                    }

                    callback(
                        newExtractorLink(
                            source = name,
                            name = linkTitle,
                            url = streamUrl,
                            type = if (isM3U8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = getQualityFromName(qualityLabel)
                            this.referer = resolvedReferer
                        }
                    )
                    mirrorEmitted = true
                }
            }

            if (mirrorEmitted) {
                foundAny = true
                workingMirrors++
            }
        }

        return foundAny
    }

    private fun buildCatalogUrl(key: String, page: Int): String? {
        return when {
            key.startsWith("feed:") -> {
                val feed = key.removePrefix("feed:")
                "$apiBase/v1/meta/anilist/$feed?page=$page&perPage=$pageSize"
            }
            key.startsWith("adv:") -> {
                val rawParams = key.removePrefix("adv:")
                val encodedParams = rawParams.split("&").mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) return@mapNotNull null
                    val k = part.substring(0, idx)
                    val v = part.substring(idx + 1)
                    "${urlEncode(k)}=${urlEncode(v)}"
                }.joinToString("&")
                "$apiBase/v1/meta/anilist/advanced-search?$encodedParams&page=$page&perPage=$pageSize"
            }
            else -> null
        }
    }

    private fun parseSearchResults(resultsNode: JsonNode): List<SearchResponse> {
        if (!resultsNode.isArray || resultsNode.size() == 0) return emptyList()
        val list = mutableListOf<SearchResponse>()
        val seenIds = HashSet<String>()

        for (item in resultsNode) {
            val id = item.path("id").textOrNull() ?: continue
            if (!seenIds.add(id)) continue

            val title = pickTitle(item.path("title")) ?: continue
            val poster = item.path("image").textOrNull() ?: item.path("cover").textOrNull()
            val formatRaw = item.path("type").textOrNull() ?: "TV"
            val tvType = resolveTvType(formatRaw)
            val currentEp = item.path("currentEpisodeCount").asInt(0)
                .takeIf { it > 0 }
                ?: item.path("currentEpisode").asInt(0).takeIf { it > 0 }
                ?: item.path("totalEpisodes").asInt(0).takeIf { it > 0 }

            list.add(
                newAnimeSearchResponse(title, "$mainUrl/anime/$id", tvType) {
                    this.posterUrl = poster
                    if (currentEp != null && currentEp > 0) {
                        addDubStatus(dubExist = false, subExist = true, subEpisodes = currentEp)
                    } else {
                        addDubStatus(dubExist = false, subExist = true)
                    }
                }
            )
        }
        return list
    }

    private fun parseRecommendations(recsNode: JsonNode): List<SearchResponse> {
        if (!recsNode.isArray || recsNode.size() == 0) return emptyList()
        val list = mutableListOf<SearchResponse>()
        val seenIds = HashSet<String>()

        for (rec in recsNode) {
            val id = rec.path("id").textOrNull() ?: continue
            if (!seenIds.add(id)) continue
            val title = pickTitle(rec.path("title")) ?: continue
            val poster = rec.path("image").textOrNull() ?: rec.path("cover").textOrNull()
            val tvType = resolveTvType(rec.path("type").textOrNull() ?: "TV")

            list.add(
                newAnimeSearchResponse(title, "$mainUrl/anime/$id", tvType) {
                    this.posterUrl = poster
                }
            )
            if (list.size >= 20) break
        }
        return list
    }

    private fun filterMainSeasonEpisodes(nodes: List<JsonNode>): List<JsonNode> {
        if (nodes.isEmpty()) return emptyList()
        val hasPositiveSeason = nodes.any { it.path("season").asInt(1) > 0 }
        val seasonFiltered = if (hasPositiveSeason) {
            nodes.filter { it.path("season").asInt(1) > 0 }
        } else {
            nodes
        }
        val airedFiltered = seasonFiltered.filter { it.path("hasAired").asBoolean(true) }
        return if (airedFiltered.isNotEmpty()) airedFiltered else seasonFiltered
    }

    private fun buildEpisodeList(
        animeId: String,
        trackType: String,
        targetCount: Int,
        epMetaByNum: Map<Int, JsonNode>,
        fallbackPoster: String?
    ): List<Episode> {
        val list = mutableListOf<Episode>()
        val count = targetCount.coerceAtLeast(1)
        for (epNum in 1..count) {
            val meta = epMetaByNum[epNum]
            val epTitle = meta?.path("title")?.textOrNull() ?: "Episode $epNum"
            val epDesc = meta?.path("description")?.textOrNull()?.let { cleanHtml(it) }?.takeIf { it.isNotBlank() }
            val epThumb = meta?.path("image")?.textOrNull() ?: fallbackPoster
            val epData = "$mainUrl/watch/$animeId?ep=$epNum&type=$trackType"

            list.add(
                newEpisode(epData) {
                    this.name = epTitle
                    this.season = 1
                    this.episode = epNum
                    this.posterUrl = epThumb
                    this.description = epDesc
                }
            )
        }
        return list
    }

    private suspend fun fetchAvailabilityServers(animeId: String): List<ServerCandidate> {
        val root = getJson("$apiBase/v1/meta/availability/$animeId/servers", apiHeaders)
            ?: getJson("$apiBase/v1/meta/availability/$animeId", apiHeaders)
            ?: return emptyList()

        if (!root.path("success").asBoolean(false)) return emptyList()
        val dataNode = root.path("data")
        if (dataNode.isMissingNode || dataNode.isNull) return emptyList()

        val result = mutableListOf<ServerCandidate>()
        val serversArr = dataNode.path("servers")
        if (serversArr.isArray && serversArr.size() > 0) {
            for (srv in serversArr) {
                val code = srv.path("code").textOrNull()?.lowercase() ?: continue
                val pAnimeId = srv.path("animeId").textOrNull()
                val hasEp = srv.path("hasEpisode").asBoolean(true)
                val totalEps = srv.path("totalEpisodes").asInt(0)
                val health = srv.path("health").path("score").asDouble(0.0)
                val types = mutableListOf<String>()
                srv.path("types").forEach { t ->
                    t.textOrNull()?.lowercase()?.let { types.add(it) }
                }
                if (types.isEmpty()) types.add("sub")
                result.add(ServerCandidate(code, pAnimeId, types, hasEp, totalEps, health))
            }
            return result
        }

        // Fallback if grouped format (h-sub/sub/dub/embed -> providers[]) is returned
        val merged = LinkedHashMap<String, ServerCandidate>()
        for (bucket in listOf("h-sub", "sub", "dub", "embed")) {
            val providersArr = dataNode.path(bucket).path("providers")
            if (!providersArr.isArray) continue
            for (srv in providersArr) {
                val code = srv.path("code").textOrNull()?.lowercase() ?: continue
                val existing = merged[code]
                if (existing == null) {
                    merged[code] = ServerCandidate(
                        code = code,
                        animeId = srv.path("animeId").textOrNull(),
                        types = mutableListOf(bucket),
                        hasEpisode = srv.path("hasEpisode").asBoolean(true),
                        totalEpisodes = srv.path("totalEpisodes").asInt(0),
                        healthScore = srv.path("health").path("score").asDouble(0.0)
                    )
                } else if (!existing.types.contains(bucket)) {
                    merged[code] = existing.copy(types = existing.types + bucket)
                }
            }
        }
        return merged.values.toList()
    }

    private suspend fun fetchServerPriority(): List<String> {
        val root = getJson("$apiBase/v1/meta/server-priority", apiHeaders)
            ?: return defaultServerFallbackOrder
        val orderNode = root.path("data").path("order")
        if (!orderNode.isArray || orderNode.size() == 0) return defaultServerFallbackOrder
        val list = mutableListOf<String>()
        orderNode.forEach { n ->
            n.textOrNull()?.lowercase()?.let { list.add(it) }
        }
        return if (list.isNotEmpty()) list else defaultServerFallbackOrder
    }

    private fun normalizeAvailabilityType(raw: String): String? {
        return when (raw.trim().lowercase()) {
            "sub" -> "sub"
            "dub" -> "dub"
            "h-sub", "hsub" -> "hsub"
            "embed" -> "embed"
            else -> null
        }
    }

    private fun resolveTvType(formatRaw: String): TvType {
        return when (formatRaw.trim().uppercase()) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA" -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun pickTitle(titleNode: JsonNode): String? {
        if (titleNode.isMissingNode || titleNode.isNull) return null
        if (titleNode.isTextual) return titleNode.textOrNull()
        return titleNode.path("english").textOrNull()
            ?: titleNode.path("romaji").textOrNull()
            ?: titleNode.path("userPreferred").textOrNull()
            ?: titleNode.path("native").textOrNull()
    }

    private fun findTmdbImage(imagesNode: JsonNode?, coverType: String): String? {
        if (imagesNode == null || !imagesNode.isArray) return null
        for (img in imagesNode) {
            if (img.path("coverType").textOrNull().equals(coverType, ignoreCase = true)) {
                val url = img.path("url").textOrNull()
                if (url != null) return url
            }
        }
        return null
    }

    private fun extractAnimeId(input: String): String? {
        val trimmed = input.trim()
        animeIdFromUrlRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        digitsOnlyRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun parseWatchTarget(data: String): WatchTarget? {
        val animeId = extractAnimeId(data) ?: return null
        val epParam = extractQueryParam(data, "ep")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val typeParam = extractQueryParam(data, "type")?.lowercase()?.takeIf {
            it == "sub" || it == "dub" || it == "hsub"
        }
        return WatchTarget(animeId, epParam, typeParam)
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

    private suspend fun getJson(url: String, headers: Map<String, String>): JsonNode? {
        return try {
            val response = app.get(url, headers = headers)
            if (response.code !in 200..299) return null
            val body = response.text
            if (body.isBlank()) return null
            mapper.readTree(body)
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanHtml(raw: String): String {
        if (raw.isBlank()) return ""
        return Jsoup.parseBodyFragment(raw).text().trim()
    }

    private fun urlEncode(value: String): String {
        return try {
            URLEncoder.encode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }

    private fun JsonNode?.textOrNull(): String? {
        if (this == null || this.isMissingNode || this.isNull) return null
        val text = this.asText("").trim()
        return if (text.isEmpty() || text.equals("null", ignoreCase = true)) null else text
    }
}
