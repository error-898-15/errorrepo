package com.errorrepo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

class YouTubeProvider : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Others,
        TvType.Live,
        TvType.TvSeries
    )

    private val service = ServiceList.YouTube

    // Verified active YouTube kiosks in NewPipeExtractor v0.26.3
    override val mainPage = mainPageOf(
        "live" to "Live",
        "trending_music" to "Music",
        "trending_gaming" to "Gaming",
        "trending_movies_and_shows" to "Movies & Shows",
        "trending_podcasts_episodes" to "Podcasts"
    )

    private val mainPageCache = mutableMapOf<String, Page?>()
    private val searchPageCache = mutableMapOf<String, Page?>()

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val kioskId = request.data
        if (page <= 1) {
            mainPageCache.remove(kioskId)
        }

        val pageData = try {
            if (page <= 1) {
                val extractor = service.kioskList.getExtractorById(kioskId, null)
                extractor.fetchPage()
                extractor.initialPage
            } else {
                val nextPage = mainPageCache[kioskId]
                    ?: return newHomePageResponse(emptyList(), false)
                val extractor = service.kioskList.getExtractorById(kioskId, nextPage)
                extractor.getPage(nextPage)
            }
        } catch (_: Throwable) {
            return newHomePageResponse(emptyList(), false)
        }

        mainPageCache[kioskId] = pageData.nextPage

        val results = pageData.items
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    name = request.name,
                    list = results,
                    isHorizontalImages = true
                )
            ),
            hasNext = pageData.hasNextPage() && pageData.nextPage != null
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val extractor = service.getSearchExtractor(query.trim())
            extractor.fetchPage()
            extractor.initialPage.items
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return newSearchResponseList(emptyList(), false)
        }

        if (page <= 1) {
            searchPageCache.remove(cleanQuery)
        }

        val pageData = try {
            val extractor = service.getSearchExtractor(cleanQuery)
            if (page <= 1) {
                extractor.fetchPage()
                extractor.initialPage
            } else {
                val nextPage = searchPageCache[cleanQuery]
                    ?: return newSearchResponseList(emptyList(), false)
                extractor.getPage(nextPage)
            }
        } catch (_: Throwable) {
            return newSearchResponseList(emptyList(), false)
        }

        searchPageCache[cleanQuery] = pageData.nextPage

        val items = pageData.items
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        return newSearchResponseList(
            items,
            hasNext = pageData.hasNextPage() && pageData.nextPage != null
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val normalizedUrl = fixUrl(url)
        return when {
            normalizedUrl.contains("/playlist?list=") ||
                (normalizedUrl.contains("/watch?v=") && normalizedUrl.contains("&list=")) ->
                loadPlaylist(normalizedUrl)
            normalizedUrl.contains("/channel/") ||
                normalizedUrl.contains("/@") ||
                normalizedUrl.contains("/c/") ||
                normalizedUrl.contains("/user/") ->
                loadChannel(normalizedUrl)
            else -> loadStream(normalizedUrl)
        }
    }

    private suspend fun loadStream(url: String): LoadResponse? {
        val info = try {
            val extractor = service.getStreamExtractor(url)
            extractor.fetchPage()
            StreamInfo.getInfo(extractor)
        } catch (_: Throwable) {
            return null
        }

        val poster = safeGet { bestImageUrl(info.thumbnails) }
        val channelAvatar = safeGet { bestImageUrl(info.uploaderAvatars) }
        val plotText = safeGet { info.description?.content?.takeIf { it.isNotBlank() } }
        val releaseYear = safeGet {
            val dateText = info.textualUploadDate
            if (!dateText.isNullOrBlank()) {
                Regex("\\b(19\\d{2}|20\\d{2})\\b").find(dateText)?.value?.toIntOrNull()
            } else {
                null
            }
        }
        val runtimeMinutes = safeGet { (info.duration / 60).toInt().takeIf { it > 0 } }
        val tagList = safeGet { info.tags?.take(8)?.takeIf { it.isNotEmpty() } }
        val creatorActors = safeGet {
            info.uploaderName
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(ActorData(Actor(it, image = channelAvatar))) }
        }
        val related = safeGet {
            info.relatedItems
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
        }

        val isLive = safeGet {
            info.streamType == StreamType.LIVE_STREAM ||
                info.streamType == StreamType.AUDIO_LIVE_STREAM
        } == true

        return if (isLive) {
            newLiveStreamLoadResponse(info.name, url, url) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = plotText
                year = releaseYear
                tags = tagList
                actors = creatorActors
                recommendations = related
            }
        } else {
            newMovieLoadResponse(info.name, url, TvType.Others, url) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = plotText
                year = releaseYear
                duration = runtimeMinutes
                tags = tagList
                actors = creatorActors
                recommendations = related
            }
        }
    }

    private suspend fun loadPlaylist(url: String): LoadResponse? {
        val extractor = try {
            service.getPlaylistExtractor(url).apply { fetchPage() }
        } catch (_: Throwable) {
            return null
        }

        val items = try {
            extractor.initialPage.items
        } catch (_: Throwable) {
            emptyList()
        }

        val episodes = items.mapIndexed { index, item ->
            newEpisode(item.url) {
                name = item.name
                season = 1
                episode = index + 1
                posterUrl = safeGet { bestImageUrl(item.thumbnails) }
                runTime = safeGet { (item.duration / 60).toInt().takeIf { it > 0 } }
                description = safeGet { item.uploaderName?.takeIf { it.isNotBlank() } }
            }
        }

        val poster = safeGet { bestImageUrl(extractor.thumbnails) }
            ?: episodes.firstOrNull()?.posterUrl
        val banner = safeGet { bestImageUrl(extractor.banners) } ?: poster
        val uploader = safeGet { extractor.uploaderName?.takeIf { it.isNotBlank() } }
        val uploaderAvatar = safeGet { bestImageUrl(extractor.uploaderAvatars) }

        return newTvSeriesLoadResponse(extractor.name, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            backgroundPosterUrl = banner
            plot = safeGet { extractor.description?.content?.takeIf { it.isNotBlank() } }
                ?: uploader?.let { "Playlist by $it" }
            actors = uploader?.let { listOf(ActorData(Actor(it, image = uploaderAvatar))) }
        }
    }

    private suspend fun loadChannel(url: String): LoadResponse? {
        val extractor = try {
            service.getChannelExtractor(url).apply { fetchPage() }
        } catch (_: Throwable) {
            return null
        }

        val tabs = safeGet { extractor.tabs } ?: emptyList()
        val videosTab = tabs.firstOrNull { tab ->
            safeGet { tab.contentFilters.contains(ChannelTabs.VIDEOS) } == true ||
                tab.url.contains("/videos")
        } ?: tabs.firstOrNull()

        val videoItems = if (videosTab != null) {
            try {
                val tabExtractor = service.getChannelTabExtractor(videosTab)
                tabExtractor.fetchPage()
                tabExtractor.initialPage.items.filterIsInstance<StreamInfoItem>()
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val episodes = videoItems.mapIndexed { index, item ->
            newEpisode(item.url) {
                name = item.name
                season = 1
                episode = index + 1
                posterUrl = safeGet { bestImageUrl(item.thumbnails) }
                runTime = safeGet { (item.duration / 60).toInt().takeIf { it > 0 } }
                description = safeGet { item.textualUploadDate?.takeIf { it.isNotBlank() } }
            }
        }

        val avatar = safeGet { bestImageUrl(extractor.avatars) }
        val banner = safeGet { bestImageUrl(extractor.banners) } ?: avatar

        return newTvSeriesLoadResponse(extractor.name, url, TvType.TvSeries, episodes) {
            posterUrl = avatar
            backgroundPosterUrl = banner
            plot = safeGet { extractor.description?.takeIf { it.isNotBlank() } }
            tags = safeGet { extractor.tags?.take(8)?.takeIf { it.isNotEmpty() } }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = fixUrl(data)

        // Delegate first to CloudStream's built-in YoutubeExtractor (supports video-only+audio, live HLS, subtitles)
        val extracted = try {
            loadExtractor(watchUrl, subtitleCallback, callback)
        } catch (_: Throwable) {
            false
        }

        if (extracted) return true

        // Fallback: inspect public StreamInfo directly for muxed progressive streams or live HLS
        val info = try {
            val extractor = service.getStreamExtractor(watchUrl)
            extractor.fetchPage()
            StreamInfo.getInfo(extractor)
        } catch (_: Throwable) {
            return false
        }

        var emitted = false

        val hlsUrl = safeGet { info.hlsUrl }
        if (!hlsUrl.isNullOrBlank()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name Live",
                    url = hlsUrl
                ) {
                    type = ExtractorLinkType.M3U8
                }
            )
            emitted = true
        }

        safeGet { info.videoStreams }?.forEach { stream ->
            val streamUrl = stream.content
            if (!streamUrl.isNullOrBlank()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name ${stream.resolution}".trim(),
                        url = streamUrl
                    ) {
                        quality = stream.height
                    }
                )
                emitted = true
            }
        }

        safeGet { info.subtitles }?.forEach { subtitle ->
            val subUrl = subtitle.content
            if (!subUrl.isNullOrBlank()) {
                subtitleCallback(
                    newSubtitleFile(
                        lang = subtitle.displayLanguageName
                            ?: subtitle.languageTag
                            ?: "Unknown",
                        url = subUrl
                    )
                )
            }
        }

        return emitted
    }

    private fun InfoItem.toSearchResponse(): SearchResponse? {
        val itemUrl = url?.takeIf { it.isNotBlank() } ?: return null
        val itemName = name?.takeIf { it.isNotBlank() } ?: return null
        val poster = safeGet { bestImageUrl(thumbnails) }

        return when (this) {
            is StreamInfoItem -> {
                val isLive = safeGet {
                    streamType == StreamType.LIVE_STREAM ||
                        streamType == StreamType.AUDIO_LIVE_STREAM
                } == true
                if (isLive) {
                    newLiveSearchResponse(itemName, itemUrl, TvType.Live) {
                        posterUrl = poster
                    }
                } else {
                    newMovieSearchResponse(itemName, itemUrl, TvType.Others) {
                        posterUrl = poster
                    }
                }
            }
            is PlaylistInfoItem -> newTvSeriesSearchResponse(itemName, itemUrl, TvType.TvSeries) {
                posterUrl = poster
            }
            is ChannelInfoItem -> newTvSeriesSearchResponse(itemName, itemUrl, TvType.TvSeries) {
                posterUrl = poster
            }
            else -> null
        }
    }

    private fun bestImageUrl(images: List<Image>?): String? {
        if (images.isNullOrEmpty()) return null
        return images.maxByOrNull { it.height }?.url ?: images.lastOrNull()?.url
    }

    private inline fun <T> safeGet(block: () -> T): T? {
        return try {
            block()
        } catch (_: Throwable) {
            null
        }
    }
}
