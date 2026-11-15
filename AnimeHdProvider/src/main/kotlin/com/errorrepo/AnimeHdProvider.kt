package com.errorrepo

import com.fasterxml.jackson.databind.JsonNode
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
import java.util.concurrent.ConcurrentHashMap

class AnimeHdProvider : MainAPI() {
    override var mainUrl = "https://animahd.com"
    override var name = "AnimeHd"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    private val mapper = jacksonObjectMapper()
    private val categoryMetaCache = ConcurrentHashMap<String, Pair<String, Int>>()

    private val defaultHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    private data class ParsedEpisodeRow(
        val season: Int,
        val episode: Int,
        val title: String,
        val watchUrl: String,
        val thumbUrl: String?
    )

    override val mainPage = mainPageOf(
        "" to "Home",
        "category/hindi-dub" to "Hindi Dub",
        "category/ongoing" to "Ongoing",
        "category/anime-movies" to "Anime Movies",
        "category/english-dub" to "English Dub",
        "category/action" to "Action",
        "category/adventure" to "Adventure",
        "category/comedy" to "Comedy",
        "category/drama" to "Drama",
        "category/fantasy" to "Fantasy",
        "category/mystery" to "Mystery",
        "category/romance" to "Romance",
        "category/sci-fi" to "Sci-Fi",
        "category/slice-of-life" to "Slice of Life",
        "category/sports" to "Sports",
        "category/supernatural" to "Supernatural",
        "category/horror" to "Horror"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val currentPage = page.coerceAtLeast(1)
        val cleanPath = request.data.trim('/')

        if (cleanPath.isEmpty()) {
            if (currentPage > 1) {
                return newHomePageResponse(emptyList(), hasNext = false)
            }
            val doc = try {
                app.get("$mainUrl/", headers = defaultHeaders).document
            } catch (_: Exception) {
                return newHomePageResponse(emptyList(), hasNext = false)
            }
            val sections = doc.select("div.animahd-carousel-section").mapNotNull { section ->
                val sectionTitle = section.selectFirst("div.animahd-section-title")?.text()?.trim()
                    ?.ifBlank { null } ?: return@mapNotNull null
                val items = parseCards(section.select("a.animahd-card"))
                if (items.isEmpty()) return@mapNotNull null
                HomePageList(
                    name = sectionTitle,
                    list = items,
                    isHorizontalImages = false
                )
            }
            return newHomePageResponse(sections, hasNext = false)
        }

        if (currentPage == 1) {
            val doc = try {
                app.get("$mainUrl/$cleanPath/", headers = defaultHeaders).document
            } catch (_: Exception) {
                return newHomePageResponse(emptyList(), hasNext = false)
            }
            val loadMoreBtn = doc.selectFirst("#animahd-load-more-cat")
            val catId = loadMoreBtn?.attr("data-cat")?.trim().orEmpty()
            val maxPage = loadMoreBtn?.attr("data-max")?.trim()?.toIntOrNull() ?: 1
            if (catId.isNotEmpty()) {
                categoryMetaCache[cleanPath] = catId to maxPage
            }
            val items = parseCards(doc.select("a.animahd-card"))
            val hasNext = items.isNotEmpty() && catId.isNotEmpty() && maxPage > 1
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

        val cachedMeta = categoryMetaCache[cleanPath] ?: run {
            val doc = try {
                app.get("$mainUrl/$cleanPath/", headers = defaultHeaders).document
            } catch (_: Exception) {
                null
            }
            val btn = doc?.selectFirst("#animahd-load-more-cat")
            val catId = btn?.attr("data-cat")?.trim().orEmpty()
            val maxPage = btn?.attr("data-max")?.trim()?.toIntOrNull() ?: 1
            if (catId.isNotEmpty()) {
                (catId to maxPage).also { categoryMetaCache[cleanPath] = it }
            } else {
                null
            }
        } ?: return newHomePageResponse(emptyList(), hasNext = false)

        val (catId, maxPage) = cachedMeta
        if (currentPage > maxPage) {
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        val ajaxText = try {
            app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                headers = defaultHeaders + ("X-Requested-With" to "XMLHttpRequest"),
                data = mapOf(
                    "action" to "animesuki_load_more_grid",
                    "cat_id" to catId,
                    "page" to currentPage.toString()
                )
            ).text
        } catch (_: Exception) {
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        val items = try {
            val root = mapper.readTree(ajaxText)
            if (!root.path("success").asBoolean(false)) {
                emptyList()
            } else {
                val htmlFragment = root.path("data").path("html").asText("")
                if (htmlFragment.isBlank()) {
                    emptyList()
                } else {
                    val fragDoc = Jsoup.parseBodyFragment(htmlFragment, mainUrl)
                    parseCards(fragDoc.select("a.animahd-card"))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = false
                )
            ),
            hasNext = items.isNotEmpty() && currentPage < maxPage
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

        val apiUrl = "$mainUrl/wp-json/wp/v2/posts?search=$encodedQuery&_embed&per_page=20&page=$currentPage"
        val restResult = try {
            val res = app.get(apiUrl, headers = defaultHeaders)
            when (res.code) {
                200 -> parseWpSearchResponse(res.text, res.headers, currentPage)
                400 -> newSearchResponseList(emptyList(), false)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
        if (restResult != null) return restResult

        val htmlSearchUrl = if (currentPage <= 1) {
            "$mainUrl/?s=$encodedQuery"
        } else {
            "$mainUrl/page/$currentPage/?s=$encodedQuery"
        }
        val doc = try {
            app.get(htmlSearchUrl, headers = defaultHeaders).document
        } catch (_: Exception) {
            return newSearchResponseList(emptyList(), false)
        }

        val items = doc.select("article").mapNotNull { article ->
            val linkEl = article.selectFirst("h2.article__title a, h2.entry-title a")
                ?: return@mapNotNull null
            val rawTitle = linkEl.text().trim()
            val itemUrl = unwrapSecRoute(linkEl.attr("href")) ?: return@mapNotNull null
            if (rawTitle.isBlank()) return@mapNotNull null

            val displayTitle = cleanPostTitle(rawTitle)
            val poster = article.selectFirst("img")?.extractImageUrl()
            val isMovie = isMovieHint(rawTitle, article.className())
            val dubStatus = resolveDubStatus(rawTitle)
            val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

            newAnimeSearchResponse(displayTitle, itemUrl, tvType) {
                posterUrl = poster
                addDubStatus(dubStatus)
            }
        }.distinctBy { it.url }

        val hasNext = items.isNotEmpty() && hasNextWpHtmlPage(doc, currentPage)
        return newSearchResponseList(items, hasNext = hasNext)
    }

    private fun parseWpSearchResponse(
        jsonText: String,
        headers: okhttp3.Headers,
        currentPage: Int
    ): SearchResponseList? {
        val root = try {
            mapper.readTree(jsonText)
        } catch (_: Exception) {
            return null
        }
        if (!root.isArray) return null

        val items = mutableListOf<SearchResponse>()
        for (post in root) {
            val link = post.path("link").asText("").trim().takeIf { it.startsWith("http") }
                ?: continue
            val rawHtmlTitle = post.path("title").path("rendered").asText("").trim()
            val rawTitle = Jsoup.parse(rawHtmlTitle).text().trim()
            if (rawTitle.isBlank()) continue

            val displayTitle = cleanPostTitle(rawTitle)
            val poster = extractWpPostPoster(post)
            val categoryIds = post.path("categories").mapNotNull { it.asInt(-1).takeIf { id -> id > 0 } }
            val isMovie = isMovieHint(rawTitle, "") || 11 in categoryIds || 41 in categoryIds
            val dubStatus = resolveDubStatus(rawTitle)
            val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

            items.add(
                newAnimeSearchResponse(displayTitle, link, tvType) {
                    posterUrl = poster
                    addDubStatus(dubStatus)
                }
            )
        }

        val totalPages = headers.entries
            .firstOrNull { it.key.equals("X-WP-TotalPages", ignoreCase = true) }
            ?.value?.toIntOrNull() ?: 1

        return newSearchResponseList(
            items.distinctBy { it.url },
            hasNext = items.isNotEmpty() && currentPage < totalPages
        )
    }

    private fun extractWpPostPoster(post: JsonNode): String? {
        val malThumb = post.path("_mal_cached_thumb").asText("").trim()
        if (malThumb.startsWith("http")) return malThumb

        val mediaNode = post.path("_embedded").path("wp:featuredmedia").firstOrNull() ?: return null
        val sizes = mediaNode.path("media_details").path("sizes")
        val mediumUrl = sizes.path("medium").path("source_url").asText("").trim()
        if (mediumUrl.startsWith("http")) return mediumUrl
        val largeUrl = sizes.path("large").path("source_url").asText("").trim()
        if (largeUrl.startsWith("http")) return largeUrl
        val sourceUrl = mediaNode.path("source_url").asText("").trim()
        return sourceUrl.takeIf { it.startsWith("http") }
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = unwrapSecRoute(url) ?: fixUrl(url)
        val doc = try {
            app.get(pageUrl, headers = defaultHeaders).document
        } catch (_: Exception) {
            return null
        }

        val rawTitle = doc.selectFirst("h1.ff-title, h1.entry-title, h1")?.text()?.trim()
            ?.ifBlank { null } ?: return null
        val displayTitle = cleanPostTitle(rawTitle)

        val poster = doc.selectFirst("div.ff-poster-wrap img")?.extractImageUrl()
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.ifBlank { null }
        val backdrop = extractBackgroundImageUrl(doc.selectFirst("div.ff-hero-bg")?.attr("style"))
            ?: poster

        val synopsis = doc.selectFirst("div.ff-synopsis")?.text()?.trim()?.ifBlank { null }
        val genres = doc.select("div.ff-genres span.ff-pill")
            .mapNotNull { it.text().trim().ifBlank { null } }
            .distinct()

        val ffMetaText = doc.selectFirst("div.ff-meta")?.text().orEmpty()
        val releaseYear = Regex("\\b(19|20)\\d{2}\\b")
            .find("$ffMetaText $rawTitle")
            ?.value?.toIntOrNull()

        val ratingText = doc.select("div.ff-meta span")
            .firstOrNull { !it.hasClass("dot") && Regex("\\d+(?:\\.\\d+)?").containsMatchIn(it.text()) }
            ?.let { Regex("(\\d+(?:\\.\\d+)?)").find(it.text())?.groupValues?.get(1) }
        val parsedScore = Score.from10(ratingText)

        val badgesText = doc.select("div.animetsu-badges-row span.badge-box")
            .joinToString(" ") { it.text().trim() }
        val resolvedStatus = when {
            badgesText.contains("FINISHED", ignoreCase = true) ||
                badgesText.contains("COMPLETED", ignoreCase = true) ||
                rawTitle.contains("Completed", ignoreCase = true) ||
                rawTitle.contains("Complete", ignoreCase = true) -> ShowStatus.Completed
            badgesText.contains("ONGOING", ignoreCase = true) ||
                badgesText.contains("RELEASING", ignoreCase = true) ||
                badgesText.contains("AIRING", ignoreCase = true) ||
                rawTitle.contains("Ongoing", ignoreCase = true) -> ShowStatus.Ongoing
            else -> null
        }

        val dubStatus = resolveDubStatus("$badgesText $rawTitle")
        val recommendations = parseCards(doc.select("div.animahd-carousel-section a.animahd-card"))

        val episodeRows = parseEpisodeRows(doc, backdrop ?: poster)
        if (episodeRows.isEmpty()) return null

        val bodyClasses = doc.body().className()
        val looksLikeMovie = isMovieHint(rawTitle, bodyClasses) ||
            (episodeRows.size == 1 && !hasEpisodeMarker(episodeRows.first().title))

        if (looksLikeMovie && episodeRows.size <= 1) {
            val movieWatchUrl = episodeRows.first().watchUrl
            return newMovieLoadResponse(displayTitle, pageUrl, TvType.AnimeMovie, movieWatchUrl) {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                plot = synopsis
                year = releaseYear
                score = parsedScore
                tags = genres.takeIf { it.isNotEmpty() }
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            }
        }

        val sortedEpisodes = episodeRows
            .sortedWith(compareBy({ it.season }, { it.episode }, { it.title }))
            .map { row ->
                newEpisode(row.watchUrl) {
                    this.name = row.title
                    this.season = row.season
                    this.episode = row.episode
                    this.posterUrl = row.thumbUrl ?: backdrop ?: poster
                }
            }

        return newAnimeLoadResponse(displayTitle, pageUrl, TvType.Anime) {
            posterUrl = poster
            backgroundPosterUrl = backdrop
            plot = synopsis
            year = releaseYear
            score = parsedScore
            tags = genres.takeIf { it.isNotEmpty() }
            showStatus = resolvedStatus
            this.recommendations = recommendations.takeIf { it.isNotEmpty() }
            addEpisodes(dubStatus, sortedEpisodes)
        }
    }

    private fun parseEpisodeRows(doc: Document, fallbackThumb: String?): List<ParsedEpisodeRow> {
        val rawRows = doc.select("a.app-ep-row-item")
        val result = mutableListOf<ParsedEpisodeRow>()

        rawRows.forEachIndexed { index, el ->
            val rawHref = el.attr("href").trim()
            val watchUrl = unwrapSecRoute(rawHref) ?: fixUrlNull(rawHref) ?: return@forEachIndexed

            val seasonAttr = el.attr("data-season").trim()
            val metaDiv = el.selectFirst("div.gdrive-ep-meta")
            val rawEpTitle = metaDiv?.selectFirst("div")?.text()?.trim()
                ?: el.text().trim()
            val cleanedEpTitle = cleanEpisodeTitle(rawEpTitle)

            val seasonNum = extractSeasonNumber(seasonAttr)
                ?: extractSeasonFromEpisodeTitle(rawEpTitle)
                ?: 1

            val epNum = extractEpisodeNumber(rawEpTitle)
                ?: extractQueryParam(watchUrl, "ep")?.toIntOrNull()?.let { epParam ->
                    if (epParam == 0) index + 1 else epParam
                }
                ?: (index + 1)

            val rawThumb = el.selectFirst("img")?.let { img ->
                unwrapSecRoute(img.attr("src")) ?: img.extractImageUrl()
            }
            val thumbUrl = rawThumb ?: fallbackThumb

            result.add(
                ParsedEpisodeRow(
                    season = seasonNum,
                    episode = epNum,
                    title = cleanedEpTitle.ifBlank { "Episode $epNum" },
                    watchUrl = watchUrl,
                    thumbUrl = thumbUrl
                )
            )
        }

        return result
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetUrl = unwrapSecRoute(data) ?: fixUrl(data)
        val playerGateUrl = resolvePlayerGateUrl(targetUrl) ?: return false

        val gateHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/"
        )

        val playerResponse = try {
            app.get(playerGateUrl, headers = gateHeaders, allowRedirects = true)
        } catch (_: Exception) {
            return false
        }

        val playerDoc = playerResponse.document
        val playerUrl = playerResponse.url.ifBlank { playerGateUrl }
        val playerOrigin = extractOrigin(playerUrl) ?: "https://youranimewatchingplace.animahd.fun"
        val playerReferer = "$playerOrigin/"

        val epMetaText = playerDoc.selectFirst("p.ep-meta")?.text().orEmpty()
        val parsedQuality = parseQualityFromText(epMetaText)
        val qualityLabel = Regex("(\\d{3,4}p)", RegexOption.IGNORE_CASE)
            .find(epMetaText)?.groupValues?.get(1) ?: "HD"

        var emitted = false
        val seenUrls = mutableSetOf<String>()

        playerDoc.select("video source[src], video[src]").forEach { el ->
            val src = el.attr("src").trim()
                .replace("&amp;", "&")
                .ifBlank { return@forEach }
            val resolved = resolveRelativeUrl(src, playerOrigin) ?: return@forEach
            if (!seenUrls.add(resolved)) return@forEach

            val linkType = if (resolved.contains(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name [$qualityLabel]",
                    url = resolved,
                    type = linkType
                ) {
                    this.quality = parsedQuality
                    this.referer = playerReferer
                }
            )
            emitted = true
        }

        if (!emitted) {
            val fallbackUrl = if (playerUrl.contains("?")) {
                "$playerUrl&fallback=1"
            } else {
                "$playerUrl?fallback=1"
            }
            val fallbackDoc = try {
                app.get(fallbackUrl, headers = gateHeaders + ("Referer" to playerReferer)).document
            } catch (_: Exception) {
                null
            }
            fallbackDoc?.select("iframe[src]")?.forEach { iframe ->
                val iframeSrc = resolveRelativeUrl(iframe.attr("src"), playerOrigin)
                    ?: return@forEach
                if (seenUrls.add(iframeSrc)) {
                    if (loadExtractor(iframeSrc, playerReferer, subtitleCallback, callback)) {
                        emitted = true
                    }
                }
            }
        }

        return emitted
    }

    private suspend fun resolvePlayerGateUrl(initialUrl: String): String? {
        if (!initialUrl.contains("animahd.com", ignoreCase = true)) {
            return initialUrl
        }
        val html = try {
            app.get(initialUrl, headers = defaultHeaders).text
        } catch (_: Exception) {
            return null
        }

        val targetStreamUrl = Regex("""const\s+targetStreamUrl\s*=\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.trim()
        if (!targetStreamUrl.isNullOrBlank() && targetStreamUrl.startsWith("http")) {
            return targetStreamUrl
        }

        val doc = Jsoup.parse(html, initialUrl)
        val directIframe = doc.selectFirst("iframe[src]")?.attr("src")?.trim()
        if (!directIframe.isNullOrBlank()) {
            return resolveRelativeUrl(directIframe, mainUrl)
        }
        return null
    }

    private fun parseCards(cards: List<Element>): List<SearchResponse> {
        return cards.mapNotNull { card ->
            val rawHref = card.attr("href").trim()
            val itemUrl = unwrapSecRoute(rawHref) ?: fixUrlNull(rawHref) ?: return@mapNotNull null

            val rawTitle = card.selectFirst("div.animahd-card-title")?.text()?.trim()
                ?.ifBlank { null } ?: return@mapNotNull null
            val displayTitle = cleanPostTitle(rawTitle)

            val poster = card.selectFirst("div.animahd-poster img")?.extractImageUrl()
                ?: extractBackgroundImageUrl(card.selectFirst("div.animahd-poster")?.attr("style"))
                ?: card.selectFirst("img")?.extractImageUrl()

            val seasonBadge = card.selectFirst("span.animahd-season")?.text()?.trim().orEmpty()
            val langBadge = card.selectFirst("div.multilingual-badge")?.text()?.trim().orEmpty()
            val isMovie = seasonBadge.equals("MOVIE", ignoreCase = true) ||
                isMovieHint(rawTitle, "")
            val dubStatus = resolveDubStatus("$langBadge $rawTitle")
            val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

            newAnimeSearchResponse(displayTitle, itemUrl, tvType) {
                posterUrl = poster
                addDubStatus(dubStatus)
            }
        }.distinctBy { it.url }
    }

    private fun hasNextWpHtmlPage(doc: Document, currentPage: Int): Boolean {
        if (doc.selectFirst("a.next.page-numbers, nav.navigation a.next") != null) {
            return true
        }
        return doc.select("ul.page-numbers a[href], div.nav-links a[href]").any { a ->
            val pageNum = Regex("/page/(\\d+)/?")
                .find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            pageNum != null && pageNum > currentPage
        }
    }

    private fun cleanPostTitle(rawTitle: String): String {
        var cleaned = rawTitle.replace('\u00A0', ' ').trim()
        cleaned = cleaned.replace(
            Regex("\\s*\\[(?:Hindi|English|Japanese|Multi|Dual|Triple|Completed|Complete|Ongoing|Airing|Anime\\s*Movie|Movie|Episode\\s*[^\\]]*|New)[^\\]]*\\]", RegexOption.IGNORE_CASE),
            ""
        )
        cleaned = cleaned.replace(
            Regex("\\s+(?:Multi|Triple|Dual)\\s*Audio\\b.*$", RegexOption.IGNORE_CASE),
            ""
        )
        cleaned = cleaned.replace(
            Regex("\\s+(?:Hindi\\s*Dubbed|Hindi\\s*Dub)\\b.*$", RegexOption.IGNORE_CASE),
            ""
        )
        cleaned = cleaned.replace(Regex("\\s{2,}"), " ").trim(' ', '-', '–', ':')
        return cleaned.ifBlank { rawTitle.trim() }
    }

    private fun cleanEpisodeTitle(rawTitle: String): String {
        var cleaned = rawTitle.replace('\u00A0', ' ').trim()
        cleaned = cleaned.replace(Regex("^\\[ANIMAHD(?:\\.COM)?\\]\\s*", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("\\.(?:mkv|mp4|avi|webm)$", RegexOption.IGNORE_CASE), "")
        return cleaned.trim()
    }

    private fun isMovieHint(title: String, classes: String): Boolean {
        return title.contains("[ANIME MOVIE]", ignoreCase = true) ||
            title.contains("[MOVIE]", ignoreCase = true) ||
            classes.contains("category-anime-movies", ignoreCase = true) ||
            classes.contains("category-animes-movies", ignoreCase = true)
    }

    private fun hasEpisodeMarker(title: String): Boolean {
        return Regex("S\\d+E\\d+|\\bE\\d+\\b|\\bEpisode\\s*\\d+", RegexOption.IGNORE_CASE)
            .containsMatchIn(title)
    }

    private fun extractSeasonNumber(text: String): Int? {
        if (text.isBlank()) return null
        return Regex("(?:Season|S)\\s*0*(\\d+)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractSeasonFromEpisodeTitle(text: String): Int? {
        return Regex("\\bS0*(\\d+)E\\d+\\b", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractEpisodeNumber(text: String): Int? {
        return Regex("\\bS\\d+E0*(\\d+)\\b", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\\b(?:Episode|Ep|E)\\s*0*(\\d+)\\b", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun resolveDubStatus(text: String): DubStatus {
        return when {
            text.contains("Hindi", ignoreCase = true) ||
                text.contains("Multilingual", ignoreCase = true) ||
                text.contains("Multi Audio", ignoreCase = true) ||
                text.contains("Dual Audio", ignoreCase = true) ||
                text.contains("Triple Audio", ignoreCase = true) ||
                text.contains("Dub", ignoreCase = true) -> DubStatus.Dubbed
            text.contains("Sub", ignoreCase = true) -> DubStatus.Subbed
            else -> DubStatus.Dubbed
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

    private fun unwrapSecRoute(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim()
            ?.replace("&#038;", "&")
            ?.replace("&amp;", "&")
            ?.takeIf { it.isNotBlank() } ?: return null
        if (trimmed.contains("sec_route=1", ignoreCase = true)) {
            val encodedP = extractQueryParam(trimmed, "p")
            if (!encodedP.isNullOrBlank()) {
                val decoded = decodeBase64(encodedP).trim()
                if (decoded.startsWith("http://", ignoreCase = true) ||
                    decoded.startsWith("https://", ignoreCase = true)
                ) {
                    return decoded
                }
            }
        }
        return fixUrlNull(trimmed)
    }

    private fun extractBackgroundImageUrl(style: String?): String? {
        if (style.isNullOrBlank()) return null
        val raw = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)
            .find(style)?.groupValues?.get(1)?.trim() ?: return null
        return unwrapSecRoute(raw)
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
        return unwrapSecRoute(raw)
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
