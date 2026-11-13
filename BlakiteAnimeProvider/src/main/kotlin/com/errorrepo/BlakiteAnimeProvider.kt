package com.errorrepo

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

class BlakiteAnimeProvider : MainAPI() {
    override var mainUrl = "https://www.blakiteanime.buzz"
    override var name = "Blakite Anime"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.AsianDrama
    )

    private val apiBase = "https://blakiteapi.xyz"
    private val cdnBase = "https://hugh.cdn.rumble.cloud/video/"
    private val mapper = jacksonObjectMapper()

    private val apiHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "User-Agent" to USER_AGENT
    )

    private data class SeasonInfo(
        val seasonNumber: Int,
        val status: String,
        val totalEpisodes: Int,
        val updatedAt: String
    )

    private data class CatalogEntry(
        val id: String,
        val categoryKey: String,
        val type: String,
        val title: String,
        val language: String,
        val status: String,
        val poster: String?,
        val backdrop: String?,
        val synopsis: String?,
        val rating: String?,
        val releaseDate: String?,
        val trailer: String?,
        val genres: List<String>,
        val keywords: List<String>,
        val seasons: Map<Int, SeasonInfo>,
        val sortTimestamp: String
    )

    @Volatile
    private var cachedCatalog: List<CatalogEntry> = emptyList()

    @Volatile
    private var lastCatalogFetchMs: Long = 0L

    private val cacheTtlMs = 15 * 60 * 1000L
    private val pageSize = 24

    override val mainPage = mainPageOf(
        "recent" to "Recently Updated",
        "series" to "Anime Series",
        "movies" to "Anime Movies",
        "dramas" to "Asian Dramas",
        "hindi" to "Hindi / ORG Audio",
        "english" to "English Dub & Sub"
    )

    private suspend fun getCatalog(): List<CatalogEntry> {
        val now = System.currentTimeMillis()
        val existing = cachedCatalog
        if (existing.isNotEmpty() && (now - lastCatalogFetchMs) < cacheTtlMs) {
            return existing
        }

        val responseText = try {
            app.get("$apiBase/api/getAllAnime.php", headers = apiHeaders).text
        } catch (_: Exception) {
            return existing
        }

        val root = try {
            mapper.readTree(responseText)
        } catch (_: Exception) {
            return existing
        }

        val dataNode = root.path("data")
        if (dataNode.isMissingNode || !dataNode.isObject) {
            return existing
        }

        val parsed = mutableListOf<CatalogEntry>()
        for (categoryKey in listOf("movies", "series", "dramas")) {
            val bucket = dataNode.path(categoryKey)
            if (!bucket.isObject) continue
            val fields = bucket.fields()
            while (fields.hasNext()) {
                val entry = fields.next()
                parseCatalogEntry(entry.key, categoryKey, entry.value)?.let { parsed.add(it) }
            }
        }

        val sorted = parsed.sortedByDescending { it.sortTimestamp }
        if (sorted.isNotEmpty()) {
            cachedCatalog = sorted
            lastCatalogFetchMs = System.currentTimeMillis()
            return sorted
        }
        return existing
    }

    private fun parseCatalogEntry(
        key: String,
        categoryKey: String,
        node: JsonNode
    ): CatalogEntry? {
        val title = node.path("title").asText("").trim()
        if (title.isEmpty()) return null

        val id = node.path("tmdbId").asText(key).ifBlank { key }
        val type = node.path("type").asText(
            when (categoryKey) {
                "movies" -> "Movie"
                "dramas" -> "Drama"
                else -> "Series"
            }
        )
        val language = node.path("language").asText("").trim()
        val status = node.path("status").asText("").trim()

        val imagesNode = node.path("IMAGES")
        val poster = imagesNode.path("poster").asText("").takeIf { it.isNotBlank() }
            ?: imagesNode.path("backdrop").asText("").takeIf { it.isNotBlank() }
        val backdrop = imagesNode.path("backdrop").asText("").takeIf { it.isNotBlank() }
            ?: poster

        val tmdbNode = node.path("TMDB_DATA")
        val synopsis = tmdbNode.path("synopsis").asText("").takeIf { it.length > 3 }
            ?: tmdbNode.path("overview").asText("").takeIf { it.length > 3 }
        val rating = tmdbNode.path("rating").asText("").takeIf { it.isNotBlank() }
        val releaseDate = tmdbNode.path("releaseDate").asText("").takeIf { it.isNotBlank() }
        val trailer = tmdbNode.path("trailer").asText("").takeIf { it.isNotBlank() }

        val genres = mutableListOf<String>()
        tmdbNode.path("genres").forEach { g ->
            g.asText("").trim().takeIf { it.isNotEmpty() }?.let { genres.add(it) }
        }

        val keywords = mutableListOf<String>()
        tmdbNode.path("keywords").forEach { kw ->
            kw.asText("").trim().takeIf { it.isNotEmpty() }?.let { keywords.add(it) }
        }

        val seasonsMap = sortedMapOf<Int, SeasonInfo>()
        var latestSeasonUpdated = ""
        val seasonsNode = node.path("seasons")
        if (seasonsNode.isObject) {
            val sFields = seasonsNode.fields()
            while (sFields.hasNext()) {
                val (sKey, sVal) = sFields.next()
                val sNum = sVal.path("seasonNumber").asInt(sKey.toIntOrNull() ?: 1)
                val totalEps = sVal.path("totalEpisodes").asInt(0)
                val sStatus = sVal.path("status").asText("").trim()
                val sUpdated = sVal.path("updatedAt").asText("")
                    .ifBlank { sVal.path("createdAt").asText("") }
                if (sUpdated > latestSeasonUpdated) {
                    latestSeasonUpdated = sUpdated
                }
                if (totalEps > 0) {
                    seasonsMap[sNum] = SeasonInfo(
                        seasonNumber = sNum,
                        status = sStatus,
                        totalEpisodes = totalEps,
                        updatedAt = sUpdated
                    )
                }
            }
        }

        val sortTs = listOf(
            node.path("updatedAt").asText(""),
            latestSeasonUpdated,
            node.path("createdAt").asText(""),
            releaseDate ?: ""
        ).firstOrNull { it.isNotBlank() } ?: ""

        return CatalogEntry(
            id = id,
            categoryKey = categoryKey,
            type = type,
            title = title,
            language = language,
            status = status,
            poster = poster,
            backdrop = backdrop,
            synopsis = synopsis,
            rating = rating,
            releaseDate = releaseDate,
            trailer = trailer,
            genres = genres,
            keywords = keywords,
            seasons = seasonsMap,
            sortTimestamp = sortTs
        )
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val catalog = getCatalog()
        val filtered = when (request.data) {
            "movies" -> catalog.filter { it.categoryKey == "movies" }
            "series" -> catalog.filter { it.categoryKey == "series" }
            "dramas" -> catalog.filter { it.categoryKey == "dramas" }
            "hindi" -> catalog.filter {
                it.language.contains("ORG", ignoreCase = true) ||
                    it.language.contains("Hindi", ignoreCase = true) ||
                    it.title.contains("Hindi", ignoreCase = true)
            }
            "english" -> catalog.filter {
                it.language.contains("English", ignoreCase = true) ||
                    it.title.contains("English", ignoreCase = true)
            }
            else -> catalog
        }

        val start = ((page - 1).coerceAtLeast(0)) * pageSize
        if (start >= filtered.size) {
            return newHomePageResponse(emptyList(), false)
        }
        val end = minOf(start + pageSize, filtered.size)
        val items = filtered.subList(start, end).map { it.toSearchResponse() }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = false
                )
            ),
            hasNext = end < filtered.size
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return filterCatalogByQuery(query).take(15).map { it.toSearchResponse() }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val matched = filterCatalogByQuery(query)
        val start = ((page - 1).coerceAtLeast(0)) * pageSize
        if (start >= matched.size) {
            return newSearchResponseList(emptyList(), false)
        }
        val end = minOf(start + pageSize, matched.size)
        val items = matched.subList(start, end).map { it.toSearchResponse() }
        return newSearchResponseList(items, hasNext = end < matched.size)
    }

    private suspend fun filterCatalogByQuery(query: String): List<CatalogEntry> {
        val clean = query.trim()
        if (clean.isEmpty()) return emptyList()
        val tokens = clean.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()

        return getCatalog().filter { entry ->
            val haystack = buildString {
                append(entry.title).append(' ')
                append(entry.language).append(' ')
                append(entry.genres.joinToString(" ")).append(' ')
                append(entry.keywords.joinToString(" "))
            }.lowercase()
            tokens.all { token -> haystack.contains(token) }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val rawId = extractQueryParam(url, "id") ?: return null
        val rawType = extractQueryParam(url, "type")?.lowercase()

        val catalog = getCatalog()
        val entry = catalog.firstOrNull { item ->
            item.id == rawId && (rawType == null ||
                item.categoryKey.startsWith(rawType.take(4)) ||
                item.type.equals(rawType, ignoreCase = true))
        } ?: catalog.firstOrNull { it.id == rawId } ?: return null

        val releaseYear = entry.releaseDate?.take(4)?.toIntOrNull()
        val parsedScore = Score.from10(entry.rating)
        val recs = catalog.asSequence()
            .filter { it.id != entry.id && it.genres.any { g -> entry.genres.contains(g) } }
            .take(12)
            .map { it.toSearchResponse() }
            .toList()

        val itemUrl = buildItemUrl(entry)
        val fullPlot = buildString {
            if (!entry.synopsis.isNullOrBlank()) {
                append(entry.synopsis)
            }
            if (entry.language.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Audio / Language: ").append(entry.language)
            }
        }.ifBlank { null }

        if (entry.categoryKey == "movies" || entry.type.equals("Movie", ignoreCase = true)) {
            return newMovieLoadResponse(entry.title, itemUrl, TvType.AnimeMovie, "Movie|${entry.id}") {
                posterUrl = entry.poster
                backgroundPosterUrl = entry.backdrop
                plot = fullPlot
                year = releaseYear
                score = parsedScore
                tags = entry.genres.takeIf { it.isNotEmpty() }
                recommendations = recs
                entry.trailer?.takeIf { it.isNotBlank() }?.let { addTrailer(listOf(it)) }
            }
        }

        val episodes = mutableListOf<Episode>()
        entry.seasons.entries.sortedBy { it.key }.forEach { (seasonNum, seasonInfo) ->
            for (epNum in 1..seasonInfo.totalEpisodes) {
                val epData = "Series|${entry.id}|${seasonNum}-${epNum}"
                episodes.add(
                    newEpisode(
                        url = epData,
                        fix = false,
                        initializer = {
                            name = "Episode $epNum"
                            season = seasonNum
                            episode = epNum
                            posterUrl = entry.backdrop ?: entry.poster
                        }
                    )
                )
            }
        }

        val resolvedStatus = when {
            entry.status.equals("Completed", ignoreCase = true) ||
                entry.seasons.values.any { it.status.equals("Completed", ignoreCase = true) } ->
                ShowStatus.Completed
            entry.status.equals("Ongoing", ignoreCase = true) ||
                entry.seasons.values.any { it.status.equals("Ongoing", ignoreCase = true) } ->
                ShowStatus.Ongoing
            else -> null
        }

        if (entry.categoryKey == "dramas" || entry.type.equals("Drama", ignoreCase = true)) {
            return newTvSeriesLoadResponse(entry.title, itemUrl, TvType.AsianDrama, episodes) {
                posterUrl = entry.poster
                backgroundPosterUrl = entry.backdrop
                plot = fullPlot
                year = releaseYear
                score = parsedScore
                tags = entry.genres.takeIf { it.isNotEmpty() }
                showStatus = resolvedStatus
                recommendations = recs
                entry.trailer?.takeIf { it.isNotBlank() }?.let { addTrailer(listOf(it)) }
            }
        }

        val dubStatus = resolveDubStatus(entry)
        return newAnimeLoadResponse(entry.title, itemUrl, TvType.Anime) {
            posterUrl = entry.poster
            backgroundPosterUrl = entry.backdrop
            plot = fullPlot
            year = releaseYear
            score = parsedScore
            tags = entry.genres.takeIf { it.isNotEmpty() }
            showStatus = resolvedStatus
            recommendations = recs
            entry.trailer?.takeIf { it.isNotBlank() }?.let { addTrailer(listOf(it)) }
            addEpisodes(dubStatus, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 2) return false

        val kind = parts[0]
        val tmdbId = parts[1]
        val episodeKey = parts.getOrNull(2)

        val apiUrl = if (kind.equals("Movie", ignoreCase = true) || episodeKey.isNullOrBlank()) {
            "$apiBase/api/get.php?tmdbId=$tmdbId"
        } else {
            "$apiBase/api/get.php?id=$episodeKey&tmdbId=$tmdbId"
        }

        val responseText = try {
            app.get(apiUrl, headers = apiHeaders).text
        } catch (_: Exception) {
            return false
        }

        val root = try {
            mapper.readTree(responseText)
        } catch (_: Exception) {
            return false
        }

        val streamData = root.path("data")
        if (streamData.isMissingNode || !streamData.isObject) return false

        val directVideoUrl = streamData.path("videoUrl").asText("").trim()
        val langLabel = streamData.path("language").asText("").trim()
        val langSuffix = if (langLabel.isNotBlank()) " ($langLabel)" else ""

        if (directVideoUrl.isNotEmpty()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name$langSuffix",
                    url = directVideoUrl,
                    type = if (directVideoUrl.contains(".m3u8", ignoreCase = true)) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                ) {
                    this.referer = "$apiBase/"
                }
            )
            return true
        }

        val dataId = streamData.path("dataId").asText("").trim()
        if (dataId.isEmpty()) return false

        val format = streamData.path("format").asText("M3U8").trim()
        val qid = streamData.path("qid").asInt(5).coerceIn(1, 5)
        val rangesRaw = streamData.path("ranges").asText("")

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
                        val range = match.groupValues[1]
                        val label = match.groupValues[2].trim()
                        rangeMap[label] = range
                    }
                }

            for (i in qualityLabels.indices.reversed()) {
                val label = qualityLabels[i]
                val range = rangeMap[label] ?: continue
                val code = qualityCodes[i]
                val m3u8Url =
                    "$cdnBase$dataId.$code.tar?r_file=chunklist.m3u8&r_type=application%2Fvnd.apple.mpegurl&r_range=$range"
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name $label$langSuffix",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = getQualityFromName(label)
                        this.referer = "$apiBase/"
                    }
                )
                emitted = true
            }
        }

        if (!emitted) {
            for (i in (qid - 1) downTo 0) {
                val label = qualityLabels[i]
                val code = qualityCodes[i]
                val mp4Url = "$cdnBase$dataId.$code.mp4"
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name $label$langSuffix",
                        url = mp4Url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = getQualityFromName(label)
                        this.referer = "$apiBase/"
                    }
                )
                emitted = true
            }
        }

        return emitted
    }

    private fun CatalogEntry.toSearchResponse(): SearchResponse {
        val url = buildItemUrl(this)
        val totalEps = seasons.values.sumOf { it.totalEpisodes }.takeIf { it > 0 }

        return when {
            categoryKey == "movies" || type.equals("Movie", ignoreCase = true) -> {
                newAnimeSearchResponse(title, url, TvType.AnimeMovie) {
                    posterUrl = poster
                    addDubStatus(resolveDubStatus(this@toSearchResponse))
                }
            }
            categoryKey == "dramas" || type.equals("Drama", ignoreCase = true) -> {
                newTvSeriesSearchResponse(title, url, TvType.AsianDrama) {
                    posterUrl = poster
                }
            }
            else -> {
                newAnimeSearchResponse(title, url, TvType.Anime) {
                    posterUrl = poster
                    addDubStatus(resolveDubStatus(this@toSearchResponse), totalEps)
                }
            }
        }
    }

    private fun resolveDubStatus(entry: CatalogEntry): DubStatus {
        val text = "${entry.language} ${entry.title}"
        return when {
            text.contains("Dub", ignoreCase = true) ||
                entry.language.equals("ORG", ignoreCase = true) -> DubStatus.Dubbed
            text.contains("Sub", ignoreCase = true) -> DubStatus.Subbed
            else -> DubStatus.None
        }
    }

    private fun buildItemUrl(entry: CatalogEntry): String {
        return "$mainUrl/2026/11/streaming.html?type=${entry.categoryKey}&id=${entry.id}"
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
}
