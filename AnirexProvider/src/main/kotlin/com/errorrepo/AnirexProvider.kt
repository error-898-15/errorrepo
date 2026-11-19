package com.errorrepo

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AnirexProvider : MainAPI() {
    override var mainUrl = "https://anirex.to"
    override var name = "Anirex"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val flixHost = "https://flixcloud.cc"
    private val mapper = jacksonObjectMapper()
    private val pageSize = 28

    private val retryScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "anirex-retry").apply { isDaemon = true }
    }

    private val defaultHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/home",
        "Accept" to "application/json, text/plain, */*"
    )

    private val flixProbeHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$flixHost/",
        "Range" to "bytes=0-1"
    )

    override val mainPage = mainPageOf(
        "home:latestEpisodes" to "Latest Episodes",
        "home:trendingWeek" to "Trending This Week",
        "home:newOnSite" to "New on Anirex",
        "search:sort=trending" to "Trending Catalog",
        "search:sort=popularity" to "Most Popular",
        "search:sort=score" to "Top Rated",
        "search:sort=latest" to "Latest Additions",
        "search:status=RELEASING&sort=trending" to "Currently Airing",
        "search:format=MOVIE&sort=popularity" to "Anime Movies",
        "search:format=OVA&sort=popularity" to "OVAs",
        "search:genres[]=Action&sort=popularity" to "Action",
        "search:genres[]=Adventure&sort=popularity" to "Adventure",
        "search:genres[]=Comedy&sort=popularity" to "Comedy",
        "search:genres[]=Drama&sort=popularity" to "Drama",
        "search:genres[]=Fantasy&sort=popularity" to "Fantasy",
        "search:genres[]=Romance&sort=popularity" to "Romance",
        "search:genres[]=Sci-Fi&sort=popularity" to "Sci-Fi",
        "home:upcoming" to "Upcoming"
    )

    private data class PlaybackRef(
        val slug: String,
        val episode: Int,
        val anilistId: Int
    )

    private data class FlixTarget(
        val aid: String,
        val instance: String,
        val audioLabel: String
    )

    private data class PreparedFlixLink(
        val target: FlixTarget,
        val sourceUrl: String,
        val resolvedUrl: String,
        val resolution: String,
        val sizeHuman: String,
        val audioType: String
    )

    private sealed class ProbeOutcome {
        object EMITTED : ProbeOutcome()
        object FAILED : ProbeOutcome()
        data class Building202(val link: PreparedFlixLink) : ProbeOutcome()
        data class Fallback(val link: PreparedFlixLink) : ProbeOutcome()
    }

    private sealed class DownloadProbeResult {
        data class Ready(val resolvedUrl: String) : DownloadProbeResult()
        object Building202 : DownloadProbeResult()
        data class Fallback(val resolvedUrl: String) : DownloadProbeResult()
        object Failed : DownloadProbeResult()
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val currentPage = page.coerceAtLeast(1)
        val key = request.data

        return when {
            key.startsWith("home:") -> {
                if (currentPage > 1) return newHomePageResponse(emptyList(), false)
                val section = key.removePrefix("home:")
                val root = getJson(
                    "$mainUrl/api/v1/homepage?featuredLimit=5&latestLimit=24&trendingLimit=24&newLimit=24&upcomingLimit=24",
                    defaultHeaders
                ) ?: return newHomePageResponse(emptyList(), false)

                val arr = root.path("data").path(section)
                val items = if (arr.isArray) {
                    arr.mapNotNull { parseAnimeCard(it) }.distinctBy { it.url }
                } else {
                    emptyList()
                }
                newHomePageResponse(
                    listOf(HomePageList(request.name, items, isHorizontalImages = false)),
                    hasNext = false
                )
            }

            key.startsWith("search:") -> {
                val filterQuery = encodeSearchFilterParams(key.removePrefix("search:"))
                val url = "$mainUrl/api/search?$filterQuery&page=$currentPage&per_page=$pageSize"
                val root = getJson(url, defaultHeaders)
                    ?: return newHomePageResponse(emptyList(), false)

                val dataArr = root.path("data")
                val items = if (dataArr.isArray) {
                    dataArr.mapNotNull { parseAnimeCard(it) }.distinctBy { it.url }
                } else {
                    emptyList()
                }

                val meta = root.path("meta")
                val lastPage = meta.path("last_page").asInt(0)
                val hasNext = items.isNotEmpty() && if (lastPage > 0) {
                    currentPage < lastPage
                } else {
                    dataArr.size() >= pageSize
                }

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
        val url = "$mainUrl/api/search?q=${urlEncode(clean)}&page=1&per_page=15"
        val root = getJson(url, defaultHeaders) ?: return emptyList()
        val dataArr = root.path("data")
        if (!dataArr.isArray) return emptyList()
        return dataArr.mapNotNull { parseAnimeCard(it) }.distinctBy { it.url }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val clean = query.trim()
        if (clean.isEmpty()) return newSearchResponseList(emptyList(), false)
        val currentPage = page.coerceAtLeast(1)
        val url = "$mainUrl/api/search?q=${urlEncode(clean)}&page=$currentPage&per_page=$pageSize"
        val root = getJson(url, defaultHeaders)
            ?: return newSearchResponseList(emptyList(), false)

        val dataArr = root.path("data")
        if (!dataArr.isArray) return newSearchResponseList(emptyList(), false)

        val items = dataArr.mapNotNull { parseAnimeCard(it) }.distinctBy { it.url }
        val meta = root.path("meta")
        val lastPage = meta.path("last_page").asInt(0)
        val hasNext = items.isNotEmpty() && if (lastPage > 0) {
            currentPage < lastPage
        } else {
            dataArr.size() >= pageSize
        }

        return newSearchResponseList(items, hasNext = hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = extractSlug(url) ?: return null
        val pageUrl = "$mainUrl/anime/$slug"

        val root = getJson("$mainUrl/api/anime/$slug", defaultHeaders) ?: return null
        val animeNode = root.path("data")
        if (animeNode.isMissingNode || animeNode.isNull) return null

        val title = pickTitle(animeNode) ?: return null
        val anilistId = animeNode.path("anilist_id").asInt(0)
        val poster = animeNode.path("cover_image_url").asText("").trim().takeIf { it.isNotBlank() }
        val backdrop = animeNode.path("banner_image_url").asText("").trim().takeIf { it.isNotBlank() }
        val plotText = cleanHtmlDescription(animeNode.path("description").asText(""))

        val seasonYear = animeNode.path("season_year").asInt(0)
        val startDateStr = animeNode.path("start_date").asText("").trim()
        val startYear = Regex("""^(\d{4})""").find(startDateStr)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val releaseYear = when {
            seasonYear > 1900 -> seasonYear
            startYear > 1900 -> startYear
            else -> null
        }

        val avgScore = animeNode.path("average_score").asDouble(0.0)
        val parsedScore = when {
            avgScore > 10.0 -> Score.from10(avgScore / 10.0)
            avgScore > 0.0 -> Score.from10(avgScore)
            else -> null
        }

        val statusRaw = animeNode.path("status").asText("").trim()
        val resolvedStatus = when {
            statusRaw.equals("FINISHED", ignoreCase = true) -> ShowStatus.Completed
            statusRaw.equals("RELEASING", ignoreCase = true) -> ShowStatus.Ongoing
            else -> null
        }

        val genresList = mutableListOf<String>()
        animeNode.path("genres").forEach { g ->
            g.asText("").trim().takeIf { it.isNotEmpty() }?.let { genresList.add(it) }
        }
        val primaryGenre = genresList.firstOrNull()

        animeNode.path("tags").forEach { t ->
            if (t.isTextual) {
                t.asText("").trim().takeIf { it.isNotEmpty() }?.let { genresList.add(it) }
            } else {
                val isSpoiler = t.path("isMediaSpoiler").asBoolean(false) ||
                    t.path("isGeneralSpoiler").asBoolean(false) ||
                    t.path("is_media_spoiler").asBoolean(false) ||
                    t.path("is_general_spoiler").asBoolean(false)
                if (!isSpoiler) {
                    t.path("name").asText("").trim().takeIf { it.isNotEmpty() }?.let { genresList.add(it) }
                }
            }
        }

        animeNode.path("studios").forEach { s ->
            val studioName = if (s.isTextual) s.asText("").trim() else s.path("name").asText("").trim()
            if (studioName.isNotEmpty()) genresList.add(studioName)
        }
        val finalTags = genresList.distinct().take(15).takeIf { it.isNotEmpty() }

        val recommendations = fetchRecommendations(primaryGenre, slug)

        val formatRaw = animeNode.path("format").asText("").trim()
        val tvType = resolveTvType(formatRaw)

        val episodeNodes = fetchAllEpisodes(slug)
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        for ((idx, epNode) in episodeNodes.withIndex()) {
            val epNum = epNode.path("episode_number").asInt(idx + 1).coerceAtLeast(1)
            val rawTitle = epNode.path("title").asText("").trim()
            val epTitle = if (rawTitle.isNotBlank() && !rawTitle.equals("Episode $epNum", ignoreCase = true)) {
                rawTitle
            } else {
                "Episode $epNum"
            }

            val audio = epNode.path("audio").asText("").trim().lowercase()
            val isDual = audio == "dual"
            val hasSub = epNode.path("has_sub").asBoolean(false) || audio == "sub" || isDual
            val hasDub = epNode.path("has_dub").asBoolean(false) || audio == "dub" || isDual
            val epData = buildPlaybackDataUrl(slug, epNum, anilistId)

            val episodeObj = newEpisode(epData) {
                this.name = epTitle
                this.season = 1
                this.episode = epNum
                this.posterUrl = poster
            }

            if (hasSub || !hasDub) {
                subEpisodes.add(episodeObj)
            }
            if (hasDub) {
                dubEpisodes.add(episodeObj)
            }
        }

        if (tvType == TvType.AnimeMovie && episodeNodes.size <= 1) {
            val movieEpNum = episodeNodes.firstOrNull()?.path("episode_number")?.asInt(1)?.coerceAtLeast(1) ?: 1
            val movieData = buildPlaybackDataUrl(slug, movieEpNum, anilistId)
            return newMovieLoadResponse(title, pageUrl, TvType.AnimeMovie, movieData) {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                plot = plotText
                year = releaseYear
                score = parsedScore
                tags = finalTags
                this.recommendations = recommendations
            }
        }

        if (subEpisodes.isEmpty() && dubEpisodes.isEmpty() && anilistId > 0) {
            val subCount = animeNode.path("sub_count").asInt(0)
            val dubCount = animeNode.path("dub_count").asInt(0)
            for (epNum in 1..subCount) {
                val epData = buildPlaybackDataUrl(slug, epNum, anilistId)
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
                val epData = buildPlaybackDataUrl(slug, epNum, anilistId)
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
        var ref = initialRef

        if (ref.anilistId <= 0) {
            val animeRoot = getJson("$mainUrl/api/anime/${ref.slug}", defaultHeaders)
            val resolvedAnilist = animeRoot?.path("data")?.path("anilist_id")?.asInt(0) ?: 0
            if (resolvedAnilist > 0) {
                ref = ref.copy(anilistId = resolvedAnilist)
            }
        }

        val embedRegex = Regex("""/e/([A-Za-z0-9_-]+)""")
        val flixTargets = mutableListOf<FlixTarget>()
        val otherEmbeds = mutableListOf<String>()

        if (ref.anilistId > 0) {
            val watchJson = getJson(
                "$mainUrl/api/v1/watch/${ref.anilistId}/${ref.episode}",
                defaultHeaders
            )
            if (watchJson != null) {
                val episodeAudio = watchJson.path("audio").asText("").trim()
                val serversNode = when {
                    watchJson.path("servers").isArray -> watchJson.path("servers")
                    watchJson.path("data").path("servers").isArray -> watchJson.path("data").path("servers")
                    else -> null
                }

                if (serversNode != null) {
                    for (srv in serversNode) {
                        val playerUrl = srv.path("player_url").asText("").trim()
                            .ifBlank { srv.path("url").asText("").trim() }
                        if (!playerUrl.startsWith("http", ignoreCase = true)) continue

                        val aid = embedRegex.find(playerUrl)?.groupValues?.get(1)
                        if (playerUrl.contains("flixcloud.", ignoreCase = true) && !aid.isNullOrBlank()) {
                            val instance = extractQueryParam(playerUrl, "v")?.trim().orEmpty().ifBlank { "2" }
                            val srvAudio = srv.path("audio").asText(episodeAudio).trim()
                            flixTargets.add(FlixTarget(aid = aid, instance = instance, audioLabel = srvAudio))
                        } else {
                            otherEmbeds.add(playerUrl)
                        }
                    }
                }
            }
        }

        if (flixTargets.isEmpty() && otherEmbeds.isEmpty()) {
            val epFallback = fetchAllEpisodes(ref.slug).firstOrNull {
                it.path("episode_number").asInt(-1) == ref.episode
            }
            val playerUrl = epFallback?.path("player")?.asText("")?.trim().orEmpty()
            if (playerUrl.startsWith("http", ignoreCase = true)) {
                val aid = embedRegex.find(playerUrl)?.groupValues?.get(1)
                if (playerUrl.contains("flixcloud.", ignoreCase = true) && !aid.isNullOrBlank()) {
                    val instance = extractQueryParam(playerUrl, "v")?.trim().orEmpty().ifBlank { "2" }
                    val epAudio = epFallback?.path("audio")?.asText("")?.trim().orEmpty()
                    flixTargets.add(FlixTarget(aid = aid, instance = instance, audioLabel = epAudio))
                } else {
                    otherEmbeds.add(playerUrl)
                }
            }
        }

        var emittedAny = false
        val fetchedSubKeys = HashSet<String>()
        val pending202 = mutableListOf<PreparedFlixLink>()
        val fallbackLinks = mutableListOf<PreparedFlixLink>()

        for (target in flixTargets.distinctBy { "${it.aid}:${it.instance}" }) {
            val embedReferer = "$flixHost/e/${target.aid}?v=${target.instance}"
            var embedAudioType = target.audioLabel

            if (fetchedSubKeys.add("${target.aid}:${target.instance}")) {
                val detected = fetchFlixEmbedMetadata(
                    aid = target.aid,
                    instance = target.instance,
                    subtitleCallback = subtitleCallback
                )
                if (!detected.isNullOrBlank()) {
                    embedAudioType = detected
                }
            }

            when (val result = resolveAndProbeFlixStream(target, embedReferer, embedAudioType, callback)) {
                ProbeOutcome.EMITTED -> emittedAny = true
                is ProbeOutcome.Building202 -> pending202.add(result.link)
                is ProbeOutcome.Fallback -> fallbackLinks.add(result.link)
                ProbeOutcome.FAILED -> Unit
            }
        }

        if (!emittedAny && pending202.isNotEmpty()) {
            val waitScheduleMs = longArrayOf(2500L, 3500L, 4500L)
            for (waitMs in waitScheduleMs) {
                pauseMillis(waitMs)
                val remaining = mutableListOf<PreparedFlixLink>()
                for (pending in pending202) {
                    when (val probe = probeFlixDownload(pending.sourceUrl)) {
                        is DownloadProbeResult.Ready -> {
                            emitFlixLink(pending.copy(resolvedUrl = probe.resolvedUrl), callback)
                            emittedAny = true
                        }
                        is DownloadProbeResult.Fallback -> {
                            fallbackLinks.add(pending.copy(resolvedUrl = probe.resolvedUrl))
                        }
                        DownloadProbeResult.Building202 -> {
                            remaining.add(pending)
                        }
                        DownloadProbeResult.Failed -> Unit
                    }
                }
                pending202.clear()
                pending202.addAll(remaining)
                if (emittedAny || pending202.isEmpty()) break
            }
        }

        if (!emittedAny) {
            for (link in fallbackLinks) {
                emitFlixLink(link, callback)
                emittedAny = true
            }
        }

        if (!emittedAny && pending202.isNotEmpty()) {
            for (pending in pending202) {
                emitFlixLink(pending, callback)
                emittedAny = true
            }
        }

        for (embedUrl in otherEmbeds.distinct()) {
            val extracted = try {
                loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
            } catch (_: Exception) {
                false
            }
            if (extracted) emittedAny = true
        }

        return emittedAny
    }

    private suspend fun pauseMillis(millis: Long) {
        if (millis <= 0L) return
        suspendCoroutine<Unit> { continuation ->
            retryScheduler.schedule(
                { continuation.resume(Unit) },
                millis,
                TimeUnit.MILLISECONDS
            )
        }
    }

    private suspend fun fetchAllEpisodes(slug: String): List<JsonNode> {
        val all = mutableListOf<JsonNode>()
        var currentPage = 1
        val guardMaxPages = 20

        while (currentPage <= guardMaxPages) {
            val root = getJson(
                "$mainUrl/api/anime/$slug/episodes?per_page=2500&page=$currentPage",
                defaultHeaders
            ) ?: break

            val arr = root.path("data")
            if (!arr.isArray || arr.size() == 0) break

            for (item in arr) {
                all.add(item)
            }

            val lastPage = root.path("meta").path("last_page").asInt(1)
            if (currentPage >= lastPage) break
            currentPage++
        }
        return all
    }

    private suspend fun fetchRecommendations(genre: String?, currentSlug: String): List<SearchResponse> {
        if (genre.isNullOrBlank()) return emptyList()
        val url = "$mainUrl/api/search?genres%5B%5D=${urlEncode(genre)}&sort=popularity&page=1&per_page=18"
        val root = getJson(url, defaultHeaders) ?: return emptyList()
        val arr = root.path("data")
        if (!arr.isArray) return emptyList()

        return arr.mapNotNull { parseAnimeCard(it) }
            .filterNot { it.url.endsWith("/$currentSlug") }
            .distinctBy { it.url }
            .take(15)
    }

    private fun parseAnimeCard(node: JsonNode): SearchResponse? {
        val slug = node.path("slug").asText("").trim()
        if (slug.isBlank()) return null
        val title = pickTitle(node) ?: return null
        val poster = node.path("cover_image_url").asText("").trim().takeIf { it.isNotBlank() }
        val format = node.path("format").asText("").trim()
        val subCount = node.path("sub_count").asInt(0)
        val dubCount = node.path("dub_count").asInt(0)
        val latestEp = node.path("latest_episode_number").asInt(0)
        val totalKnown = maxOf(subCount, dubCount, latestEp).takeIf { it > 0 }
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

    private suspend fun fetchFlixEmbedMetadata(
        aid: String,
        instance: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): String? {
        val raw = try {
            app.get(
                "$flixHost/e/$aid/__data.json?v=$instance",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            ).text
        } catch (_: Exception) {
            return null
        }

        val nodes = decodeSvelteNodes(raw)
        val seenUrls = HashSet<String>()
        var audioType: String? = null

        for (node in nodes) {
            val foundAudio = node.path("audio_type").asText("").trim()
            if (foundAudio.isNotEmpty() && audioType == null) {
                audioType = foundAudio
            }

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
        return audioType
    }

    private suspend fun resolveAndProbeFlixStream(
        target: FlixTarget,
        embedReferer: String,
        audioType: String,
        callback: (ExtractorLink) -> Unit
    ): ProbeOutcome {
        val raw = try {
            app.get(
                "$flixHost/d/${target.aid}/__data.json?v=${target.instance}",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedReferer
                )
            ).text
        } catch (_: Exception) {
            return ProbeOutcome.FAILED
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

        val finalUrl = downloadUrl ?: return ProbeOutcome.FAILED
        val prepared = PreparedFlixLink(
            target = target,
            sourceUrl = finalUrl,
            resolvedUrl = finalUrl,
            resolution = resolution,
            sizeHuman = sizeHuman,
            audioType = audioType
        )

        return when (val probe = probeFlixDownload(finalUrl)) {
            is DownloadProbeResult.Ready -> {
                emitFlixLink(prepared.copy(resolvedUrl = probe.resolvedUrl), callback)
                ProbeOutcome.EMITTED
            }
            DownloadProbeResult.Building202 -> ProbeOutcome.Building202(prepared)
            is DownloadProbeResult.Fallback -> ProbeOutcome.Fallback(prepared.copy(resolvedUrl = probe.resolvedUrl))
            DownloadProbeResult.Failed -> ProbeOutcome.FAILED
        }
    }

    private suspend fun probeFlixDownload(finalUrl: String): DownloadProbeResult {
        val probe = try {
            app.get(
                finalUrl,
                headers = flixProbeHeaders,
                allowRedirects = false
            )
        } catch (_: Exception) {
            return DownloadProbeResult.Fallback(finalUrl)
        }

        if (probe.code in 300..399) {
            val location = (probe.headers["Location"] ?: probe.headers["location"]).orEmpty().trim()
            if (location.startsWith("http", ignoreCase = true)) {
                return DownloadProbeResult.Ready(location)
            }
        }

        if (probe.code == 202) {
            return DownloadProbeResult.Building202
        }

        if (probe.code == 429) {
            return DownloadProbeResult.Fallback(finalUrl)
        }

        val ct = (probe.headers["Content-Type"] ?: probe.headers["content-type"]).orEmpty()
        if (probe.code in 200..299 &&
            !ct.contains("json", ignoreCase = true) &&
            !ct.contains("text/html", ignoreCase = true)
        ) {
            return DownloadProbeResult.Ready(finalUrl)
        }

        return DownloadProbeResult.Failed
    }

    private suspend fun emitFlixLink(
        link: PreparedFlixLink,
        callback: (ExtractorLink) -> Unit
    ) {
        val audioBadge = when {
            link.audioType.equals("dual", ignoreCase = true) -> "Sub + Dub"
            link.audioType.equals("dub", ignoreCase = true) -> "Dub"
            link.audioType.equals("native", ignoreCase = true) ||
                link.audioType.equals("sub", ignoreCase = true) -> "Sub"
            link.target.audioLabel.equals("dual", ignoreCase = true) -> "Sub + Dub"
            else -> ""
        }
        val labelParts = listOfNotNull(
            name,
            "FlixCloud",
            audioBadge.takeIf { it.isNotEmpty() },
            link.sizeHuman.takeIf { it.isNotEmpty() }
        )

        callback(
            newExtractorLink(
                source = name,
                name = labelParts.joinToString(" · "),
                url = link.resolvedUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                if (link.resolvedUrl.contains("flixcloud.", ignoreCase = true)) {
                    this.referer = "$flixHost/"
                }
                this.quality = getQualityFromName(link.resolution)
            }
        )
    }

    /**
     * Decodes SvelteKit __data.json devalue indexed node arrays safely into Jackson JsonNodes.
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
                    obj.replace(
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
                    obj.replace(
                        entry.key,
                        resolveDevalueValue(entry.value, dataArr, memo, visiting, depth + 1)
                    )
                }
                obj
            }
            raw.isArray -> {
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
            "OVA", "ONA", "SPECIAL", "TV_SHORT", "TV SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun pickTitle(node: JsonNode): String? {
        return listOf(
            node.path("title_english").asText(""),
            node.path("title_romaji").asText(""),
            node.path("title_native").asText(""),
            node.path("title").asText("")
        ).firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }?.trim()
    }

    private fun cleanHtmlDescription(raw: String): String? {
        if (raw.isBlank() || raw.equals("null", ignoreCase = true)) return null
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

    private fun buildPlaybackDataUrl(slug: String, episode: Int, anilistId: Int): String {
        return "$mainUrl/watch/$slug/$episode?anilist=$anilistId"
    }

    private fun parsePlaybackRef(data: String): PlaybackRef? {
        val clean = data.trim()
        val slug = extractSlug(clean) ?: return null
        val path = try {
            URI(clean).path.orEmpty()
        } catch (_: Exception) {
            clean.substringBefore("?").substringBefore("#")
        }.trim('/')
        val segments = path.split('/').filter { it.isNotBlank() }
        val watchIdx = segments.indexOfFirst { it.equals("watch", true) }
        val pathEp = if (watchIdx >= 0 && watchIdx + 2 < segments.size) {
            segments[watchIdx + 2].toIntOrNull()
        } else {
            null
        }
        val queryEp = extractQueryParam(clean, "ep")?.toIntOrNull()
        val ep = (pathEp ?: queryEp ?: 1).coerceAtLeast(1)
        val anilistId = extractQueryParam(clean, "anilist")?.toIntOrNull() ?: 0
        return PlaybackRef(slug = slug, episode = ep, anilistId = anilistId)
    }

    private fun encodeSearchFilterParams(raw: String): String {
        return raw.split("&")
            .filter { it.isNotBlank() }
            .joinToString("&") { part ->
                val pair = part.split("=", limit = 2)
                if (pair.size == 2) {
                    "${urlEncode(pair[0])}=${urlEncode(pair[1])}"
                } else {
                    urlEncode(part)
                }
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

    private fun urlEncode(value: String): String {
        return try {
            URLEncoder.encode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }
}
