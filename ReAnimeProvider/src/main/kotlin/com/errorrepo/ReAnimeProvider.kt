package com.errorrepo

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class ReAnimeProvider : MainAPI() {
    override var mainUrl = "https://reanime.cz"
    override var name = "Re:ANIME"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val flixHost = "https://flixcloud.cc"
    private val mapper = jacksonObjectMapper()
    private val pageSize = 24

    private val defaultHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "Accept" to "application/json, text/plain, */*"
    )

    // Stores next_cursor per cursor-based feed and page number
    private val feedCursors = ConcurrentHashMap<String, ConcurrentHashMap<Int, String>>()

    override val mainPage = mainPageOf(
        "feed:latest-aired" to "Latest Aired",
        "feed:new-on-site" to "New on Site",
        "trending" to "Trending Now",
        "search:sort=popularity_desc" to "Most Popular",
        "search:sort=score_desc" to "Top Rated",
        "search:format=MOVIE&playable=true&sort=popularity_desc" to "Anime Movies",
        "search:format=OVA&playable=true&sort=popularity_desc" to "OVAs",
        "search:genre=Action&sort=popularity_desc" to "Action",
        "search:genre=Adventure&sort=popularity_desc" to "Adventure",
        "search:genre=Comedy&sort=popularity_desc" to "Comedy",
        "search:genre=Drama&sort=popularity_desc" to "Drama",
        "search:genre=Fantasy&sort=popularity_desc" to "Fantasy",
        "search:genre=Romance&sort=popularity_desc" to "Romance",
        "search:genre=Sci-Fi&sort=popularity_desc" to "Sci-Fi",
        "feed:upcoming" to "Upcoming"
    )

    private data class PlaybackRef(
        val slug: String,
        val episode: Int,
        val anilistId: Int,
        val malId: Int,
        val tmdbId: Int,
        val tmdbSeason: Int
    )

    private data class FlixServer(
        val serverName: String,
        val aid: String,
        val instance: String,
        val hardsub: Boolean,
        val hasSub: Boolean,
        val hasDub: Boolean
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val currentPage = page.coerceAtLeast(1)
        val key = request.data

        return when {
            key == "trending" -> {
                if (currentPage > 1) return newHomePageResponse(emptyList(), false)
                val items = fetchTrendingItems()
                newHomePageResponse(
                    listOf(HomePageList(request.name, items, isHorizontalImages = false)),
                    hasNext = false
                )
            }

            key.startsWith("feed:") -> {
                val feedName = key.removePrefix("feed:")
                val cursorMap = feedCursors.getOrPut(feedName) { ConcurrentHashMap() }
                if (currentPage == 1) {
                    cursorMap.clear()
                }
                val cursor = if (currentPage > 1) cursorMap[currentPage - 1] else null
                if (currentPage > 1 && cursor.isNullOrBlank()) {
                    return newHomePageResponse(emptyList(), false)
                }

                val urlBuilder = StringBuilder("$mainUrl/api/v1/home/$feedName?limit=$pageSize")
                if (!cursor.isNullOrBlank()) {
                    urlBuilder.append("&cursor=").append(urlEncode(cursor))
                }

                val root = getJson(urlBuilder.toString(), defaultHeaders)
                    ?: return newHomePageResponse(emptyList(), false)

                val dataArr = root.path("data")
                val items = if (dataArr.isArray) {
                    dataArr.mapNotNull { parseSearchCard(it) }.distinctBy { it.url }
                } else {
                    emptyList()
                }

                val nextCursor = root.path("next_cursor").asText("").trim()
                val hasMore = root.path("has_more").asBoolean(false) && nextCursor.isNotEmpty()
                if (hasMore) {
                    cursorMap[currentPage] = nextCursor
                }

                newHomePageResponse(
                    listOf(HomePageList(request.name, items, isHorizontalImages = false)),
                    hasNext = items.isNotEmpty() && hasMore
                )
            }

            key.startsWith("search:") -> {
                val filterQuery = key.removePrefix("search:")
                val offset = (currentPage - 1) * pageSize
                val url = "$mainUrl/api/v1/search?$filterQuery&limit=$pageSize&offset=$offset"
                val root = getJson(url, defaultHeaders)
                    ?: return newHomePageResponse(emptyList(), false)

                val resultsNode = root.path("results")
                val total = root.path("total").asInt(0)
                val items = if (resultsNode.isArray) {
                    resultsNode.mapNotNull { parseSearchCard(it) }.distinctBy { it.url }
                } else {
                    emptyList()
                }
                val hasNext = items.isNotEmpty() &&
                    ((total > 0 && offset + resultsNode.size() < total) || resultsNode.size() >= pageSize)

                newHomePageResponse(
                    listOf(HomePageList(request.name, items, isHorizontalImages = false)),
                    hasNext = hasNext
                )
            }

            else -> newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        val clean = query.trim()
        if (clean.isEmpty()) return emptyList()
        val url = "$mainUrl/api/v1/search?q=${urlEncode(clean)}&limit=15&offset=0"
        val root = getJson(url, defaultHeaders) ?: return emptyList()
        val resultsNode = root.path("results")
        if (!resultsNode.isArray) return emptyList()
        return resultsNode.mapNotNull { parseSearchCard(it) }.distinctBy { it.url }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val clean = query.trim()
        if (clean.isEmpty()) return newSearchResponseList(emptyList(), false)
        val currentPage = page.coerceAtLeast(1)
        val offset = (currentPage - 1) * pageSize
        val url = "$mainUrl/api/v1/search?q=${urlEncode(clean)}&limit=$pageSize&offset=$offset"
        val root = getJson(url, defaultHeaders)
            ?: return newSearchResponseList(emptyList(), false)

        val resultsNode = root.path("results")
        if (!resultsNode.isArray) return newSearchResponseList(emptyList(), false)
        val total = root.path("total").asInt(0)
        val items = resultsNode.mapNotNull { parseSearchCard(it) }.distinctBy { it.url }
        val hasNext = items.isNotEmpty() &&
            ((total > 0 && offset + resultsNode.size() < total) || resultsNode.size() >= pageSize)

        return newSearchResponseList(items, hasNext = hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = extractSlug(url) ?: return null
        val pageUrl = "$mainUrl/anime/$slug"

        val animeNode = getJson("$mainUrl/api/v1/anime/$slug", defaultHeaders) ?: return null
        val title = pickTitle(animeNode.path("title")) ?: return null

        val anilistId = animeNode.path("anilist_id").asInt(0)
        val malId = animeNode.path("mal_id").asInt(0)
        val tmdbId = animeNode.path("themoviedb_id").asInt(0)
        val tmdbSeason = animeNode.path("external_seasons").path("tmdb").asInt(1).coerceAtLeast(1)

        val poster = pickCover(animeNode.path("cover_image"))
        val backdrop = animeNode.path("banner_image").asText("").trim().takeIf { it.isNotBlank() }
        val plotText = cleanHtmlDescription(animeNode.path("description").asText(""))

        val seasonYear = animeNode.path("season_year").asInt(0)
        val startYear = animeNode.path("start_date").path("year").asInt(0)
        val releaseYear = when {
            seasonYear > 1900 -> seasonYear
            startYear > 1900 -> startYear
            else -> null
        }

        val avgScore = animeNode.path("average_score").asDouble(0.0)
        val malScore = animeNode.path("mal_score").asDouble(0.0)
        val parsedScore = when {
            avgScore > 0.0 -> Score.from10(avgScore / 10.0)
            malScore > 0.0 -> Score.from10(malScore)
            else -> null
        }

        val statusRaw = animeNode.path("status").asText("").trim()
        val resolvedStatus = when {
            statusRaw.equals("Finished", ignoreCase = true) -> ShowStatus.Completed
            statusRaw.equals("Releasing", ignoreCase = true) -> ShowStatus.Ongoing
            else -> null
        }

        val genresList = mutableListOf<String>()
        animeNode.path("genres").forEach { g ->
            g.asText("").trim().takeIf { it.isNotEmpty() }?.let { genresList.add(it) }
        }
        animeNode.path("tags").forEach { t ->
            if (!t.path("is_general_spoiler").asBoolean(false) &&
                !t.path("is_media_spoiler").asBoolean(false)
            ) {
                t.path("name").asText("").trim().takeIf { it.isNotEmpty() }?.let { genresList.add(it) }
            }
        }
        val finalTags = genresList.distinct().take(15).takeIf { it.isNotEmpty() }

        val trailerNode = animeNode.path("trailer")
        val trailerUrl = if (
            trailerNode.path("site").asText("").equals("youtube", ignoreCase = true) &&
            trailerNode.path("id").asText("").isNotBlank()
        ) {
            "https://www.youtube.com/watch?v=${trailerNode.path("id").asText("").trim()}"
        } else {
            null
        }

        val recommendations = fetchRecommendations(slug)

        val formatRaw = animeNode.path("format").asText("").trim()
        val tvType = resolveTvType(formatRaw)

        val episodeNodes = fetchAllEpisodes(slug)
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        for ((idx, epNode) in episodeNodes.withIndex()) {
            val epNum = epNode.path("episode_number").asInt(idx + 1).coerceAtLeast(1)
            val epTitle = epNode.path("title").asText("").trim().ifBlank { "Episode $epNum" }
            val epDesc = cleanHtmlDescription(epNode.path("description").asText(""))
            val epThumb = epNode.path("thumbnail").asText("").trim().takeIf { it.isNotBlank() } ?: poster
            val isSub = epNode.path("subbed").asBoolean(false)
            val isDub = epNode.path("dubbed").asBoolean(false)

            val epData = buildPlaybackDataUrl(slug, epNum, anilistId, malId, tmdbId, tmdbSeason)
            val episodeObj = newEpisode(epData) {
                this.name = epTitle
                this.season = 1
                this.episode = epNum
                this.posterUrl = epThumb
                this.description = epDesc
            }

            if (isSub || !isDub) {
                subEpisodes.add(episodeObj)
            }
            if (isDub) {
                dubEpisodes.add(episodeObj)
            }
        }

        if (tvType == TvType.AnimeMovie && episodeNodes.size <= 1) {
            val movieEpNum = episodeNodes.firstOrNull()?.path("episode_number")?.asInt(1)?.coerceAtLeast(1) ?: 1
            val movieData = buildPlaybackDataUrl(slug, movieEpNum, anilistId, malId, tmdbId, tmdbSeason)
            return newMovieLoadResponse(title, pageUrl, TvType.AnimeMovie, movieData) {
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

        // Fallback if a movie/special or single-entry title has an episode count without episode rows
        if (subEpisodes.isEmpty() && dubEpisodes.isEmpty() && (anilistId > 0 || malId > 0 || tmdbId > 0)) {
            val subCount = animeNode.path("subbed").asInt(0)
            val dubCount = animeNode.path("dubbed").asInt(0)
            for (epNum in 1..subCount) {
                val epData = buildPlaybackDataUrl(slug, epNum, anilistId, malId, tmdbId, tmdbSeason)
                subEpisodes.add(
                    newEpisode(epData) {
                        this.name = "Episode $epNum"
                        this.season = 1
                        this.episode = epNum
                        this.posterUrl = poster
                    }
                )
            }
            for (epNum in 1..dubCount) {
                val epData = buildPlaybackDataUrl(slug, epNum, anilistId, malId, tmdbId, tmdbSeason)
                dubEpisodes.add(
                    newEpisode(epData) {
                        this.name = "Episode $epNum"
                        this.season = 1
                        this.episode = epNum
                        this.posterUrl = poster
                    }
                )
            }
        }

        return newAnimeLoadResponse(title, pageUrl, tvType) {
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
                addEpisodes(DubStatus.Subbed, subEpisodes.sortedBy { it.episode ?: 0 })
            }
            if (dubEpisodes.isNotEmpty()) {
                addEpisodes(DubStatus.Dubbed, dubEpisodes.sortedBy { it.episode ?: 0 })
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val initialRef = parsePlaybackRef(data) ?: return false

        // Resolve missing external IDs if opened directly via watch/anime URL
        var ref = initialRef
        if (ref.anilistId <= 0 && ref.malId <= 0 && ref.tmdbId <= 0) {
            val animeNode = getJson("$mainUrl/api/v1/anime/${ref.slug}", defaultHeaders)
            if (animeNode != null) {
                ref = ref.copy(
                    anilistId = animeNode.path("anilist_id").asInt(0),
                    malId = animeNode.path("mal_id").asInt(0),
                    tmdbId = animeNode.path("themoviedb_id").asInt(0),
                    tmdbSeason = animeNode.path("external_seasons").path("tmdb").asInt(1).coerceAtLeast(1)
                )
            }
        }

        var emittedAny = false

        // 1. Check /api/v1/watch/{slug}?ep={ep} for any direct episode_links if provided
        val watchJson = getJson("$mainUrl/api/v1/watch/${ref.slug}?ep=${ref.episode}", defaultHeaders)
        val episodeLinks = watchJson?.path("episode_links")
        if (episodeLinks != null && episodeLinks.isArray && episodeLinks.size() > 0) {
            for (linkNode in episodeLinks) {
                val linkUrl = linkNode.path("link").asText("").trim()
                val srvName = linkNode.path("name").asText(name).trim()
                if (linkUrl.isBlank()) continue
                if (linkUrl.contains(".m3u8", ignoreCase = true) || linkUrl.contains(".mp4", ignoreCase = true)) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name - $srvName",
                            url = linkUrl,
                            type = if (linkUrl.contains(".m3u8", ignoreCase = true)) {
                                ExtractorLinkType.M3U8
                            } else {
                                ExtractorLinkType.VIDEO
                            }
                        ) {
                            this.referer = "$mainUrl/"
                        }
                    )
                    emittedAny = true
                } else {
                    val extracted = try {
                        loadExtractor(linkUrl, "$mainUrl/", subtitleCallback, callback)
                    } catch (_: Exception) {
                        false
                    }
                    if (extracted) emittedAny = true
                }
            }
        }

        // 2. Query Re:ANIME's primary Flix endpoint: /api/flix/{anilistId}/{ep}?mal=...
        val flixUrl = buildFlixApiUrl(ref)
        if (flixUrl != null) {
            val flixJson = getJson(flixUrl, defaultHeaders)
            val serversNode = flixJson?.path("servers")
            if (serversNode != null && serversNode.isArray && serversNode.size() > 0) {
                val grouped = groupFlixServers(serversNode)
                val fetchedSubKeys = HashSet<String>()

                for (srv in grouped) {
                    val embedReferer = "$flixHost/e/${srv.aid}?v=${srv.instance}"

                    // Fetch subtitles from /e/{aid}/__data.json?v={instance}
                    if (!srv.hardsub && fetchedSubKeys.add("${srv.aid}:${srv.instance}")) {
                        fetchFlixSubtitles(srv.aid, srv.instance, embedReferer, subtitleCallback)
                    }

                    // Fetch direct MKV/MP4 download stream from /d/{aid}/__data.json?v={instance}
                    val ok = fetchFlixDirectStream(srv, embedReferer, emittedAny, callback)
                    if (ok) {
                        emittedAny = true
                    }
                }
            }
        }

        return emittedAny
    }

    private suspend fun fetchTrendingItems(): List<SearchResponse> {
        val text = try {
            app.get("$mainUrl/home/__data.json", headers = defaultHeaders).text
        } catch (_: Exception) {
            return emptyList()
        }
        val nodes = decodeSvelteNodes(text)
        for (node in nodes) {
            val trending = node.path("homeData").path("trending")
            if (trending.isArray && trending.size() > 0) {
                return trending.mapNotNull { parseSearchCard(it) }.distinctBy { it.url }
            }
        }
        return emptyList()
    }

    private suspend fun fetchAllEpisodes(slug: String): List<JsonNode> {
        val all = mutableListOf<JsonNode>()
        var offset = 0
        val batchLimit = 500
        var pagesGuard = 0

        while (pagesGuard < 20) {
            pagesGuard++
            val root = getJson(
                "$mainUrl/api/v1/anime/$slug/episodes?limit=$batchLimit&offset=$offset",
                defaultHeaders
            ) ?: break

            val arr = root.path("data")
            if (!arr.isArray || arr.size() == 0) break

            for (item in arr) {
                all.add(item)
            }

            val total = root.path("total").asInt(0)
            offset += arr.size()
            if (total <= 0 || offset >= total || arr.size() < batchLimit) {
                break
            }
        }
        return all
    }

    private suspend fun fetchRecommendations(slug: String): List<SearchResponse> {
        val root = getJson("$mainUrl/api/v1/anime/$slug/recommendations", defaultHeaders)
            ?: return emptyList()
        val recs = root.path("recommendations")
        if (!recs.isArray) return emptyList()
        return recs.mapNotNull { node ->
            val recSlug = node.path("id").asText("").trim()
                .ifBlank { node.path("anime_id").asText("").trim() }
            if (recSlug.isBlank()) return@mapNotNull null
            val recTitle = pickTitle(node.path("title"))
                ?: node.path("title").asText("").trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val recPoster = pickCover(node.path("cover_image"))
            val format = node.path("format").asText("").trim()
            val itemUrl = "$mainUrl/anime/$recSlug"
            val subCount = node.path("subbed").asInt(0)
            val dubCount = node.path("dubbed").asInt(0)
            val tvType = resolveTvType(format)

            newAnimeSearchResponse(recTitle, itemUrl, tvType) {
                posterUrl = recPoster
                if (subCount > 0) addDubStatus(DubStatus.Subbed, subCount)
                if (dubCount > 0) addDubStatus(DubStatus.Dubbed, dubCount)
            }
        }.distinctBy { it.url }.take(20)
    }

    private fun parseSearchCard(node: JsonNode): SearchResponse? {
        val slug = node.path("anime_id").asText("").trim()
        if (slug.isBlank()) return null
        val title = pickTitle(node.path("title")) ?: return null
        val poster = pickCover(node.path("cover_image"))
        val format = node.path("format").asText("").trim()
        val subCount = node.path("subbed").asInt(0)
        val dubCount = node.path("dubbed").asInt(0)
        val epNumber = node.path("episode").path("episode_number").asInt(0)
        val totalKnown = maxOf(subCount, dubCount, epNumber).takeIf { it > 0 }
        val tvType = resolveTvType(format)
        val itemUrl = "$mainUrl/anime/$slug"

        return newAnimeSearchResponse(title, itemUrl, tvType) {
            posterUrl = poster
            if (tvType == TvType.AnimeMovie) {
                if (subCount > 0) addDubStatus(DubStatus.Subbed)
                if (dubCount > 0) addDubStatus(DubStatus.Dubbed)
            } else {
                if (subCount > 0) addDubStatus(DubStatus.Subbed, subCount)
                if (dubCount > 0) addDubStatus(DubStatus.Dubbed, dubCount)
                if (subCount == 0 && dubCount == 0 && totalKnown != null) {
                    addDubStatus(DubStatus.None, totalKnown)
                }
            }
        }
    }

    private fun buildFlixApiUrl(ref: PlaybackRef): String? {
        if (ref.anilistId <= 0 && ref.malId <= 0 && ref.tmdbId <= 0) return null
        val params = mutableListOf<String>()
        if (ref.malId > 0) {
            params.add("mal=${ref.malId}")
        }
        if (ref.anilistId <= 0 && ref.tmdbId > 0) {
            params.add("tmdb=${ref.tmdbId}")
            params.add("season=${ref.tmdbSeason}")
        }
        val query = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
        return "$mainUrl/api/flix/${ref.anilistId.coerceAtLeast(0)}/${ref.episode}$query"
    }

    private fun groupFlixServers(serversNode: JsonNode): List<FlixServer> {
        val byKey = LinkedHashMap<String, FlixServer>()
        val embedRegex = Regex("""/e/([A-Za-z0-9_-]+)""")

        for (srv in serversNode) {
            val link = srv.path("dataLink").asText("").trim()
            val aid = embedRegex.find(link)?.groupValues?.get(1) ?: continue
            val instance = extractQueryParam(link, "v")?.trim().orEmpty().ifBlank { "1" }
            val srvName = srv.path("serverName").asText("Server").trim()
            val dataType = srv.path("dataType").asText("").trim().lowercase()
            val hardsub = srv.path("hardsub").asBoolean(false)

            val mapKey = "$aid:$instance"
            val existing = byKey[mapKey]
            val isSub = dataType == "sub" || existing?.hasSub == true
            val isDub = dataType == "dub" || existing?.hasDub == true

            byKey[mapKey] = FlixServer(
                serverName = srvName,
                aid = aid,
                instance = instance,
                hardsub = hardsub || (existing?.hardsub == true),
                hasSub = isSub,
                hasDub = isDub
            )
        }
        return byKey.values.toList()
    }

    private suspend fun fetchFlixSubtitles(
        aid: String,
        instance: String,
        embedReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val raw = try {
            app.get(
                "$flixHost/e/$aid/__data.json?v=$instance",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            ).text
        } catch (_: Exception) {
            return
        }

        val nodes = decodeSvelteNodes(raw)
        val seenUrls = HashSet<String>()
        for (node in nodes) {
            val subs = node.path("subtitles")
            if (!subs.isArray) continue
            for (sub in subs) {
                val subUrl = sub.path("url").asText("").trim()
                if (!subUrl.startsWith("http", ignoreCase = true) || !seenUrls.add(subUrl)) continue
                val rawLang = sub.path("language").asText("").trim().ifBlank { "Subtitles" }
                val cleanLang = rawLang
                    .replace(Regex("""\s*\(Track\s+\d+\s*\([^)]+\)\)\s*""", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .ifBlank { rawLang }
                subtitleCallback(
                    newSubtitleFile(
                        lang = cleanLang,
                        url = subUrl
                    )
                )
            }
        }
    }

    private suspend fun fetchFlixDirectStream(
        srv: FlixServer,
        embedReferer: String,
        alreadyHasLink: Boolean,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val raw = try {
            app.get(
                "$flixHost/d/${srv.aid}/__data.json?v=${srv.instance}",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedReferer
                )
            ).text
        } catch (_: Exception) {
            return false
        }

        val nodes = decodeSvelteNodes(raw)
        var downloadUrl: String? = null
        var resolution = ""
        var sizeHuman = ""

        for (node in nodes) {
            val videoNode = node.path("video")
            val dlNode = node.path("download")
            val fileId = videoNode.path("fileId").asText("").trim()
            val base = dlNode.path("base").asText("").trim().trimEnd('/')
            val token = dlNode.path("token").asText("").trim()
            if (fileId.isNotEmpty() && base.startsWith("http") && token.isNotEmpty()) {
                resolution = videoNode.path("resolution").asText("").trim()
                sizeHuman = videoNode.path("sizeHuman").asText("").trim()
                downloadUrl = "$base/download/$fileId?token=${urlEncode(token)}"
                break
            }
        }

        val finalUrl = downloadUrl ?: return false

        // Verify the stream is actually ready (cached -> 206 video/x-matroska; building -> 202 JSON)
        val probeHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$flixHost/",
            "Range" to "bytes=0-1"
        )

        var ready = false
        val maxAttempts = if (alreadyHasLink) 1 else 3
        for (attempt in 0 until maxAttempts) {
            if (attempt > 0) {
                delay(2500L)
            }
            val probe = try {
                app.get(finalUrl, headers = probeHeaders)
            } catch (_: Exception) {
                null
            } ?: continue

            val ct = (probe.headers["Content-Type"] ?: probe.headers["content-type"]).orEmpty()
            if (probe.code in 200..299 &&
                probe.code != 202 &&
                !ct.contains("json", ignoreCase = true) &&
                !ct.contains("text/html", ignoreCase = true)
            ) {
                ready = true
                break
            }
            if (probe.code != 202) {
                break
            }
        }

        if (!ready) return false

        val audioBadge = when {
            srv.hardsub -> "Hardsub"
            srv.hasSub && srv.hasDub -> "Sub + Dub"
            srv.hasDub -> "Dub"
            srv.hasSub -> "Sub"
            else -> ""
        }
        val labelParts = buildList {
            add(name)
            add(srv.serverName)
            if (audioBadge.isNotEmpty()) add(audioBadge)
            if (sizeHuman.isNotEmpty()) add(sizeHuman)
        }

        callback(
            newExtractorLink(
                source = name,
                name = labelParts.joinToString(" · "),
                url = finalUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "$flixHost/"
                this.quality = getQualityFromName(resolution)
            }
        )
        return true
    }

    /**
     * Decodes SvelteKit __data.json devalue indexed node arrays safely into standard Jackson JsonNodes.
     */
    private fun decodeSvelteNodes(jsonText: String): List<JsonNode> {
        val root = try {
            mapper.readTree(jsonText)
        } catch (_: Exception) {
            return emptyList()
        }
        val nodesArr = root.path("nodes")
        if (!nodesArr.isArray) return emptyList()

        val out = mutableListOf<JsonNode>()
        for (node in nodesArr) {
            if (node == null || node.isNull) continue
            if (node.path("type").asText("") != "data") continue
            val dataArr = node.path("data")
            if (!dataArr.isArray || dataArr.size() == 0) continue

            val memo = HashMap<Int, JsonNode>()
            val visiting = HashSet<Int>()
            val decoded = resolveDevalueIndex(dataArr, 0, memo, visiting, 0)
            if (decoded.isObject) {
                out.add(decoded)
            }
        }
        return out
    }

    private fun resolveDevalueValue(
        raw: JsonNode,
        dataArr: JsonNode,
        memo: MutableMap<Int, JsonNode>,
        visiting: MutableSet<Int>,
        depth: Int
    ): JsonNode {
        if (depth > 64) return NullNode.instance
        return when {
            raw.isInt || raw.isLong -> {
                val idx = raw.asInt(-1)
                if (idx < 0 || idx >= dataArr.size()) NullNode.instance
                else resolveDevalueIndex(dataArr, idx, memo, visiting, depth + 1)
            }
            raw.isObject -> {
                val obj: ObjectNode = mapper.createObjectNode()
                val fields = raw.fields()
                while (fields.hasNext()) {
                    val entry = fields.next()
                    obj.set<JsonNode>(
                        entry.key,
                        resolveDevalueValue(entry.value, dataArr, memo, visiting, depth + 1)
                    )
                }
                obj
            }
            raw.isArray -> {
                val arr: ArrayNode = mapper.createArrayNode()
                for (child in raw) {
                    arr.add(resolveDevalueValue(child, dataArr, memo, visiting, depth + 1))
                }
                arr
            }
            else -> raw
        }
    }

    private fun resolveDevalueIndex(
        dataArr: JsonNode,
        idx: Int,
        memo: MutableMap<Int, JsonNode>,
        visiting: MutableSet<Int>,
        depth: Int
    ): JsonNode {
        if (depth > 64 || idx < 0 || idx >= dataArr.size()) return NullNode.instance
        memo[idx]?.let { return it }
        if (!visiting.add(idx)) return NullNode.instance

        val raw = dataArr.get(idx)
        val result: JsonNode = when {
            raw == null || raw.isNull -> NullNode.instance
            raw.isObject -> {
                val obj: ObjectNode = mapper.createObjectNode()
                memo[idx] = obj
                val fields = raw.fields()
                while (fields.hasNext()) {
                    val entry = fields.next()
                    obj.set<JsonNode>(
                        entry.key,
                        resolveDevalueValue(entry.value, dataArr, memo, visiting, depth + 1)
                    )
                }
                obj
            }
            raw.isArray -> {
                // Handle special SvelteKit tagged tuples like ["Date", "..."] safely
                if (raw.size() == 2 && raw.get(0).isTextual &&
                    (raw.get(0).asText() == "Date" || raw.get(0).asText() == "BigInt")
                ) {
                    raw.get(1)
                } else {
                    val arr: ArrayNode = mapper.createArrayNode()
                    memo[idx] = arr
                    for (child in raw) {
                        arr.add(resolveDevalueValue(child, dataArr, memo, visiting, depth + 1))
                    }
                    arr
                }
            }
            else -> raw
        }

        memo[idx] = result
        visiting.remove(idx)
        return result
    }

    private suspend fun getJson(url: String, headers: Map<String, String>): JsonNode? {
        val text = try {
            val res = app.get(url, headers = headers)
            if (res.code !in 200..299) return null
            res.text
        } catch (_: Exception) {
            return null
        }
        return try {
            mapper.readTree(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveTvType(format: String): TvType {
        return when (format.trim().uppercase()) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL", "TV SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun pickTitle(titleNode: JsonNode): String? {
        if (titleNode.isTextual) {
            return titleNode.asText("").trim().takeIf { it.isNotEmpty() }
        }
        return listOf(
            titleNode.path("english").asText(""),
            titleNode.path("user_preferred").asText(""),
            titleNode.path("romaji").asText(""),
            titleNode.path("native").asText("")
        ).firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun pickCover(coverNode: JsonNode): String? {
        return listOf(
            coverNode.path("extra_large").asText(""),
            coverNode.path("large").asText(""),
            coverNode.path("medium").asText("")
        ).firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun cleanHtmlDescription(raw: String): String? {
        if (raw.isBlank()) return null
        val withNewlines = raw.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        val cleaned = Jsoup.parse(withNewlines).wholeText()
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        return cleaned.takeIf { it.isNotEmpty() }
    }

    private fun extractSlug(rawUrl: String): String? {
        val clean = rawUrl.trim()
        if (clean.isEmpty()) return null
        val path = try {
            URI(clean).path.orEmpty()
        } catch (_: Exception) {
            clean.substringBefore("?").substringBefore("#")
        }.trim('/')

        val segments = path.split('/').filter { it.isNotBlank() }
        val idx = segments.indexOfFirst { it.equals("anime", true) || it.equals("watch", true) }
        return when {
            idx >= 0 && idx + 1 < segments.size -> segments[idx + 1]
            segments.isNotEmpty() -> segments.last()
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private fun buildPlaybackDataUrl(
        slug: String,
        episode: Int,
        anilistId: Int,
        malId: Int,
        tmdbId: Int,
        tmdbSeason: Int
    ): String {
        return "$mainUrl/watch/$slug?ep=$episode&anilist=$anilistId&mal=$malId&tmdb=$tmdbId&season=$tmdbSeason"
    }

    private fun parsePlaybackRef(data: String): PlaybackRef? {
        val slug = extractSlug(data) ?: return null
        val ep = extractQueryParam(data, "ep")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val anilistId = extractQueryParam(data, "anilist")?.toIntOrNull() ?: 0
        val malId = extractQueryParam(data, "mal")?.toIntOrNull() ?: 0
        val tmdbId = extractQueryParam(data, "tmdb")?.toIntOrNull() ?: 0
        val tmdbSeason = extractQueryParam(data, "season")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        return PlaybackRef(
            slug = slug,
            episode = ep,
            anilistId = anilistId,
            malId = malId,
            tmdbId = tmdbId,
            tmdbSeason = tmdbSeason
        )
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

    private fun urlEncode(value: String): String {
        return try {
            URLEncoder.encode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }
}
