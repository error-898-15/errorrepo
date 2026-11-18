package com.errorrepo

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

class BilibiliProvider : MainAPI() {
    override var mainUrl = "https://www.bilibili.tv"
    override var name = "BiliBili"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
        TvType.Others
    )

    private val apiBase = "https://api.bilibili.tv/intl/gateway"
    private val mapper = jacksonObjectMapper()

    @Volatile
    private var cachedBuvid3: String? = null

    private val mediaHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "recommended" to "Recommended For You",
        "timeline" to "Anime Schedule",
        "anime_rec" to "Recommended Anime"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val currentPage = page.coerceAtLeast(1)

        return when (request.data) {
            "timeline" -> {
                if (currentPage > 1) return newHomePageResponse(emptyList(), false)
                val url = "$apiBase/web/v2/ogv/timeline?platform=web&s_locale=en_US"
                val root = getApiJson(url) ?: return newHomePageResponse(emptyList(), false)
                val seenIds = HashSet<String>()
                val items = ArrayList<SearchResponse>()

                root.path("data").path("items").forEach { dayNode ->
                    dayNode.path("cards").forEach { card ->
                        val seasonId = card.path("season_id").asText("").trim()
                        if (seasonId.isNotEmpty() && seenIds.add(seasonId)) {
                            parseOgvCard(card)?.let { items.add(it) }
                        }
                    }
                }

                newHomePageResponse(
                    listOf(
                        HomePageList(
                            name = request.name,
                            list = items,
                            isHorizontalImages = false
                        )
                    ),
                    hasNext = false
                )
            }

            "anime_rec" -> {
                val url = "$apiBase/web/v2/home/recommend?platform=web&s_locale=en_US&pn=$currentPage&ps=40"
                val root = getApiJson(url) ?: return newHomePageResponse(emptyList(), false)
                val cards = root.path("data").path("cards")
                val seenIds = HashSet<String>()
                val items = ArrayList<SearchResponse>()

                cards.forEach { card ->
                    if (card.path("type").asText("") == "ogv") {
                        val seasonId = card.path("season_id").asText("").trim()
                        if (seasonId.isNotEmpty() && seenIds.add(seasonId)) {
                            parseOgvCard(card)?.let { items.add(it) }
                        }
                    }
                }

                newHomePageResponse(
                    listOf(
                        HomePageList(
                            name = request.name,
                            list = items,
                            isHorizontalImages = false
                        )
                    ),
                    hasNext = cards.isArray && cards.size() > 0 && currentPage < 15
                )
            }

            else -> {
                val url = "$apiBase/web/v2/home/recommend?platform=web&s_locale=en_US&pn=$currentPage&ps=20"
                val root = getApiJson(url) ?: return newHomePageResponse(emptyList(), false)
                val cards = root.path("data").path("cards")
                val seenUrls = HashSet<String>()
                val items = ArrayList<SearchResponse>()

                cards.forEach { card ->
                    val parsed = when (card.path("type").asText("")) {
                        "ogv" -> parseOgvCard(card)
                        "ugc" -> parseUgcCard(card)
                        else -> null
                    }
                    if (parsed != null && seenUrls.add(parsed.url)) {
                        items.add(parsed)
                    }
                }

                newHomePageResponse(
                    listOf(
                        HomePageList(
                            name = request.name,
                            list = items,
                            isHorizontalImages = true
                        )
                    ),
                    hasNext = cards.isArray && cards.size() > 0
                )
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val clean = query.trim()
        if (clean.isEmpty()) return newSearchResponseList(emptyList(), false)

        val currentPage = page.coerceAtLeast(1)
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val url = "$apiBase/web/v2/search_v2?keyword=$encoded&platform=web&s_locale=en_US&pn=$currentPage&ps=20"
        val root = getApiJson(url) ?: return newSearchResponseList(emptyList(), false)

        val modules = root.path("data").path("modules")
        val seenUrls = HashSet<String>()
        val items = ArrayList<SearchResponse>()
        var hasMore = false

        modules.forEach { module ->
            val moduleType = module.path("type").asText("")
            val moduleItems = module.path("items")
            if ((moduleType == "ogv" || moduleType == "ugc") && moduleItems.isArray && moduleItems.size() >= 20) {
                hasMore = true
            }
            moduleItems.forEach { item ->
                val parsed = when (moduleType) {
                    "ogv" -> parseOgvCard(item)
                    "ugc" -> parseUgcCard(item)
                    else -> null
                }
                if (parsed != null && seenUrls.add(parsed.url)) {
                    items.add(parsed)
                }
            }
        }

        return newSearchResponseList(items, hasMore)
    }

    override suspend fun load(url: String): LoadResponse? {
        val ogvMatch = Regex("""/(?:play|media)/(\d+)(?:/(\d+))?""").find(url)
        if (ogvMatch != null) {
            val seasonId = ogvMatch.groupValues[1]
            return loadOgvSeason(seasonId)
        }

        val ugcMatch = Regex("""/video/(\d+)""").find(url)
        if (ugcMatch != null) {
            val aid = ugcMatch.groupValues[1]
            return loadUgcVideo(aid)
        }

        return null
    }

    private suspend fun loadOgvSeason(seasonId: String): LoadResponse? {
        val infoUrl = "$apiBase/web/v2/ogv/play/season_info?season_id=$seasonId&platform=web&s_locale=en_US"
        val infoRoot = getApiJson(infoUrl) ?: return null
        val season = infoRoot.path("data").path("season")
        if (season.isMissingNode || season.isNull) return null

        val title = season.path("title").asText("").trim()
        if (title.isEmpty()) return null

        val poster = cleanImageUrl(
            season.path("vertical_cover").asText("").ifBlank {
                season.path("horizontal_cover").asText("")
            }
        )
        val bgPoster = cleanImageUrl(season.path("horizontal_cover").asText(""))
        val description = season.path("description").asText("").trim().ifBlank { null }
        val playerDate = season.path("player_date").asText("").ifBlank {
            season.path("player_time").asText("")
        }
        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(playerDate)?.groupValues?.get(1)?.toIntOrNull()
        val tags = season.path("styles").mapNotNull { style ->
            style.path("title").asText("").trim().takeIf { it.isNotEmpty() }
        }.distinct()

        val status = when {
            season.path("is_finished").asBoolean(false) -> ShowStatus.Completed
            else -> ShowStatus.Ongoing
        }

        val episodesUrl = "$apiBase/web/v2/ogv/play/episodes?season_id=$seasonId&platform=web&s_locale=en_US"
        val epRoot = getApiJson(episodesUrl)
        val episodes = ArrayList<Episode>()
        val seenEpIds = HashSet<String>()
        var seq = 1

        epRoot?.path("data")?.path("sections")?.forEach { section ->
            section.path("episodes").forEach { ep ->
                val epId = ep.path("episode_id").asText("").trim()
                if (epId.isNotEmpty() && seenEpIds.add(epId)) {
                    val shortTitle = ep.path("short_title_display").asText("").trim()
                    val longTitle = ep.path("long_title_display").asText("").trim()
                    val displayTitle = ep.path("title_display").asText("").trim()
                    val isVip = ep.path("limit").asInt(0) == 2 ||
                        ep.path("limit_text").asText("").equals("Premium", ignoreCase = true)

                    val baseEpName = when {
                        longTitle.isNotEmpty() -> longTitle
                        displayTitle.isNotEmpty() -> displayTitle
                        shortTitle.isNotEmpty() -> shortTitle
                        else -> "Episode $seq"
                    }
                    val epName = if (isVip) "$baseEpName [VIP]" else baseEpName
                    val epNum = Regex("""^E?(\d+)""").find(shortTitle)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""^E?(\d+)""").find(displayTitle)?.groupValues?.get(1)?.toIntOrNull()
                        ?: seq
                    val thumb = cleanImageUrl(ep.path("cover").asText(""))

                    episodes.add(
                        newEpisode("$mainUrl/en/play/$seasonId/$epId") {
                            this.name = epName
                            this.episode = epNum
                            this.posterUrl = thumb
                        }
                    )
                    seq++
                }
            }
        }

        if (episodes.isEmpty()) {
            val firstEpId = season.path("first_episode").path("episode_id").asText("").trim()
            if (firstEpId.isNotEmpty()) {
                episodes.add(
                    newEpisode("$mainUrl/en/play/$seasonId/$firstEpId") {
                        this.name = season.path("first_episode").path("title_display").asText("Episode 1")
                        this.episode = 1
                    }
                )
            }
        }

        val canonicalUrl = "$mainUrl/en/play/$seasonId"
        return newAnimeLoadResponse(title, canonicalUrl, TvType.Anime) {
            this.posterUrl = poster
            if (!bgPoster.isNullOrBlank()) {
                this.backgroundPosterUrl = bgPoster
            }
            this.plot = description
            this.year = year
            if (tags.isNotEmpty()) {
                this.tags = tags
            }
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private suspend fun loadUgcVideo(aid: String): LoadResponse? {
        val infoUrl = "$apiBase/web/v2/ugc/play/info?aid=$aid&platform=web&s_locale=en_US"
        val infoRoot = getApiJson(infoUrl) ?: return null
        val archive = infoRoot.path("data").path("archive")
        if (archive.isMissingNode || archive.isNull) return null

        val title = archive.path("title").asText("").trim()
        if (title.isEmpty()) return null

        val poster = cleanImageUrl(archive.path("cover").asText(""))
        val desc = archive.path("desc").asText("").trim()
        val uploader = archive.path("uploader").path("name").asText("").trim()
        val views = archive.path("stat").path("views").asText("").trim()
        val metaLine = listOfNotNull(
            uploader.takeIf { it.isNotEmpty() }?.let { "Uploader: $it" },
            views.takeIf { it.isNotEmpty() }
        ).joinToString(" • ")

        val plotText = when {
            desc.isNotEmpty() && metaLine.isNotEmpty() -> "$desc\n\n$metaLine"
            desc.isNotEmpty() -> desc
            metaLine.isNotEmpty() -> metaLine
            else -> null
        }

        val pubDate = archive.path("formatted_pub_date").asText("")
        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(pubDate)?.groupValues?.get(1)?.toIntOrNull()
        val durationSec = archive.path("duration").asInt(0)
        val durationMin = if (durationSec > 0) (durationSec / 60).coerceAtLeast(1) else null

        val canonicalUrl = "$mainUrl/en/video/$aid"
        return newMovieLoadResponse(title, canonicalUrl, TvType.Others, canonicalUrl) {
            this.posterUrl = poster
            this.plot = plotText
            this.year = year
            this.duration = durationMin
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (epId, aid) = parsePlayTarget(data) ?: return false

        val queryParam = if (epId != null) "ep_id=$epId" else "aid=$aid"
        val primaryPlayUrl = "$apiBase/web/playurl?s_locale=en_US&platform=web&$queryParam&qn=80&device=wap&tf=0&type=0"
        val fallbackPlayUrl = "$apiBase/web/playurl?s_locale=en_US&platform=web&$queryParam"

        val firstRoot = getApiJson(primaryPlayUrl)
        val playRoot = if (
            firstRoot == null ||
            firstRoot.path("code").asInt(-1) != 0 ||
            firstRoot.path("data").path("playurl").path("video").none { hasPlayableVideoUrl(it) }
        ) {
            getApiJson(fallbackPlayUrl) ?: return false
        } else {
            firstRoot
        }

        if (playRoot.path("code").asInt(-1) != 0) {
            return false
        }

        val playurl = playRoot.path("data").path("playurl")
        val audioList = playurl.path("audio_resource")

        var bestAudioPrimary: String? = null
        var bestAudioBackup: String? = null
        var bestAudioScore = -1

        audioList.forEach { aud ->
            val aUrl = aud.path("url").asText("").trim()
            val aBackup = aud.path("backup_url").firstOrNull()?.asText("")?.trim()?.takeIf { it.isNotEmpty() }
            if (aUrl.isNotEmpty() || aBackup != null) {
                val score = aud.path("quality").asInt(0).coerceAtLeast(aud.path("bandwidth").asInt(0))
                if (score >= bestAudioScore) {
                    bestAudioScore = score
                    bestAudioPrimary = aUrl.takeIf { it.isNotEmpty() }
                    bestAudioBackup = aBackup
                }
            }
        }

        val videoNodes = playurl.path("video").filter { hasPlayableVideoUrl(it) }.sortedWith(
            compareByDescending<JsonNode> {
                val codecId = it.path("video_resource").path("codec_id").asInt(0)
                if (codecId == 7) 1 else 0
            }.thenByDescending {
                it.path("stream_info").path("quality").asInt(0)
            }
        )

        var emitted = false
        for (vNode in videoNodes) {
            val vr = vNode.path("video_resource")
            val sr = vNode.path("stream_info")
            val primaryVideoUrl = vr.path("url").asText("").trim()
            val backupVideoUrl = vr.path("backup_url").firstOrNull()?.asText("")?.trim()?.takeIf { it.isNotEmpty() }
            if (primaryVideoUrl.isEmpty() && backupVideoUrl == null) continue

            val qn = sr.path("quality").asInt(0)
            val descWords = sr.path("desc_words").asText("").trim()
            val codecs = vr.path("codecs").asText("")
            val codecId = vr.path("codec_id").asInt(0)
            val codecLabel = when {
                codecId == 7 || codecs.startsWith("avc1", ignoreCase = true) -> "H264"
                codecId == 12 || codecs.startsWith("hev1", ignoreCase = true) || codecs.startsWith("hvc1", ignoreCase = true) -> "HEVC"
                codecs.startsWith("av01", ignoreCase = true) -> "AV1"
                else -> ""
            }
            val labelSuffix = listOf(descWords, codecLabel).filter { it.isNotEmpty() }.joinToString(" ")
            val linkQuality = mapQuality(qn, vr.path("height").asInt(0))

            if (backupVideoUrl != null) {
                val audioForBackup = bestAudioBackup ?: bestAudioPrimary
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name Mirror 1 ($labelSuffix)".trim(),
                        url = backupVideoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = linkQuality
                        this.headers = mediaHeaders
                        if (!audioForBackup.isNullOrBlank()) {
                            this.audioTracks = listOf(
                                newAudioFile(audioForBackup) {
                                    this.headers = mediaHeaders
                                }
                            )
                        }
                    }
                )
                emitted = true
            }

            if (primaryVideoUrl.isNotEmpty()) {
                val audioForPrimary = bestAudioPrimary ?: bestAudioBackup
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name ($labelSuffix)".trim(),
                        url = primaryVideoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = linkQuality
                        this.headers = mediaHeaders
                        if (!audioForPrimary.isNullOrBlank()) {
                            this.audioTracks = listOf(
                                newAudioFile(audioForPrimary) {
                                    this.headers = mediaHeaders
                                }
                            )
                        }
                    }
                )
                emitted = true
            }
        }

        // Load subtitles cleanly (converted from BiliBili JSON to SRT inside getVideoInterceptor)
        val subQueryParam = if (epId != null) "episode_id=$epId" else "aid=$aid"
        val subEndpoint = "$apiBase/web/v2/subtitle?$subQueryParam&s_locale=en_US&platform=web"
        val subRoot = getApiJson(subEndpoint)
        if (subRoot != null && subRoot.path("code").asInt(-1) == 0) {
            val seenSubUrls = HashSet<String>()
            val videoSubs = subRoot.path("data").path("video_subtitle")
            if (videoSubs.isArray && videoSubs.size() > 0) {
                videoSubs.forEach { sub ->
                    val langName = sub.path("lang").asText("").ifBlank {
                        sub.path("lang_key").asText("English")
                    }
                    val jsonSubUrl = sub.path("srt").path("url").asText("").trim()
                    if (jsonSubUrl.isNotEmpty() && seenSubUrls.add(jsonSubUrl)) {
                        subtitleCallback.invoke(
                            newSubtitleFile(langName, "$jsonSubUrl#bili.srt") {
                                this.headers = mediaHeaders
                            }
                        )
                    }
                }
            } else {
                subRoot.path("data").path("subtitles").forEach { sub ->
                    val langName = sub.path("lang").asText("").ifBlank {
                        sub.path("lang_key").asText("English")
                    }
                    val rawUrl = sub.path("url").asText("").trim()
                    if (rawUrl.contains(".json", ignoreCase = true) && seenSubUrls.add(rawUrl)) {
                        subtitleCallback.invoke(
                            newSubtitleFile(langName, "$rawUrl#bili.srt") {
                                this.headers = mediaHeaders
                            }
                        )
                    }
                }
            }
        }

        return emitted
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            val reqUrl = request.url
            val isBiliJsonSub = reqUrl.host.endsWith("bstarstatic.com", ignoreCase = true) &&
                reqUrl.encodedPath.endsWith(".json", ignoreCase = true)

            val response = chain.proceed(request)
            if (!isBiliJsonSub || !response.isSuccessful) {
                return@Interceptor response
            }

            val rawJson = response.body?.string().orEmpty()
            val srtText = convertBiliSubJsonToSrt(rawJson)
            val newBody = srtText.toResponseBody("application/x-subrip; charset=utf-8".toMediaTypeOrNull())

            response.newBuilder()
                .removeHeader("Content-Length")
                .removeHeader("Content-Encoding")
                .header("Content-Type", "application/x-subrip; charset=utf-8")
                .body(newBody)
                .build()
        }
    }

    private fun hasPlayableVideoUrl(node: JsonNode): Boolean {
        val vr = node.path("video_resource")
        val primary = vr.path("url").asText("").trim()
        val backup = vr.path("backup_url").firstOrNull()?.asText("")?.trim().orEmpty()
        return primary.isNotEmpty() || backup.isNotEmpty()
    }

    private fun convertBiliSubJsonToSrt(rawJson: String): String {
        return try {
            val root = mapper.readTree(rawJson)
            val body = root.path("body")
            if (!body.isArray || body.size() == 0) return ""

            val sb = StringBuilder()
            var index = 1
            for (line in body) {
                val fromSec = line.path("from").asDouble(-1.0)
                val toSec = line.path("to").asDouble(-1.0)
                val content = line.path("content").asText("").trim()
                if (fromSec < 0 || toSec <= 0 || content.isEmpty()) continue

                sb.append(index++)
                    .append('\n')
                    .append(formatSrtTime(fromSec))
                    .append(" --> ")
                    .append(formatSrtTime(toSec))
                    .append('\n')
                    .append(content)
                    .append("\n\n")
            }
            sb.toString()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun formatSrtTime(seconds: Double): String {
        val totalMs = (seconds * 1000.0).toLong().coerceAtLeast(0L)
        val hours = totalMs / 3_600_000L
        val minutes = (totalMs % 3_600_000L) / 60_000L
        val secs = (totalMs % 60_000L) / 1_000L
        val ms = totalMs % 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, secs, ms)
    }

    private fun parsePlayTarget(data: String): Pair<String?, String?>? {
        val trimmed = data.trim()
        Regex("""/play/\d+/(\d+)""").find(trimmed)?.groupValues?.get(1)?.let { epId ->
            return epId to null
        }
        Regex("""/video/(\d+)""").find(trimmed)?.groupValues?.get(1)?.let { aid ->
            return null to aid
        }
        Regex("""ogv\|\d+\|(\d+)""").find(trimmed)?.groupValues?.get(1)?.let { epId ->
            return epId to null
        }
        Regex("""ugc\|(\d+)""").find(trimmed)?.groupValues?.get(1)?.let { aid ->
            return null to aid
        }
        return null
    }

    private fun parseOgvCard(card: JsonNode): SearchResponse? {
        val seasonId = card.path("season_id").asText("").trim()
        val title = card.path("title").asText("").trim()
        if (seasonId.isEmpty() || title.isEmpty()) return null
        val cover = cleanImageUrl(card.path("cover").asText(""))

        return newAnimeSearchResponse(title, "$mainUrl/en/play/$seasonId", TvType.Anime) {
            this.posterUrl = cover
        }
    }

    private fun parseUgcCard(card: JsonNode): SearchResponse? {
        val aid = card.path("aid").asText("").trim()
        val title = card.path("title").asText("").trim()
        if (aid.isEmpty() || title.isEmpty()) return null
        val cover = cleanImageUrl(card.path("cover").asText(""))

        return newMovieSearchResponse(title, "$mainUrl/en/video/$aid", TvType.Others) {
            this.posterUrl = cover
        }
    }

    private fun cleanImageUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
    }

    private fun mapQuality(qn: Int, height: Int): Int {
        return when (qn) {
            112, 80 -> 1080
            64 -> 720
            32 -> 480
            16 -> 360
            6 -> 240
            5 -> 144
            else -> if (height > 0) height else 480
        }
    }

    private suspend fun getBuvid3(forceRefresh: Boolean = false): String {
        if (!forceRefresh) {
            cachedBuvid3?.let { return it }
        }
        var extracted: String? = null
        try {
            val res = app.get(
                "$mainUrl/en",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9"
                )
            )
            for (cookieLine in res.headers.values("Set-Cookie")) {
                val match = Regex("""buvid3=([^;]+)""").find(cookieLine)
                if (match != null) {
                    extracted = match.groupValues[1].trim()
                    break
                }
            }
        } catch (_: Throwable) {
        }

        val finalBuvid3 = extracted?.takeIf { it.isNotEmpty() }
            ?: "${UUID.randomUUID().toString().uppercase(Locale.US)}${(10000..99999).random()}infoc"
        cachedBuvid3 = finalBuvid3
        return finalBuvid3
    }

    private suspend fun getApiJson(url: String): JsonNode? {
        for (attempt in 0..1) {
            val buvid3 = getBuvid3(forceRefresh = attempt > 0)
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/en",
                "Accept" to "application/json, text/plain, */*",
                "Cookie" to "buvid3=$buvid3;"
            )
            val text = try {
                app.get(url, headers = headers).text.trim()
            } catch (_: Throwable) {
                continue
            }
            if (text.startsWith("{")) {
                return try {
                    mapper.readTree(text)
                } catch (_: Throwable) {
                    null
                }
            }
        }
        return null
    }
}
