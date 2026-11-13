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
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
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
        } catch (_: Exception) {
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
        } catch (_: Exception) {
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
        } catch (_: Exception) {
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
        val linkType = try {
            service.getLinkTypeByUrl(normalizedUrl)
        } catch (_: Exception) {
            StreamingService.LinkType.NONE
        }

        return when (linkType) {
            StreamingService.LinkType.PLAYLIST -> loadPlaylist(normalizedUrl)
            StreamingService.LinkType.CHANNEL -> loadChannel(normalizedUrl)
            StreamingService.LinkType.STREAM -> loadStream(normalizedUrl)
            StreamingService.LinkType.NONE -> null
        }
    }

    private suspend fun loadStream(url: String): LoadResponse? {
        val info = try {
            StreamInfo.getInfo(url)
        } catch (_: Exception) {
            return null
        }

        val poster = bestImageUrl(info.thumbnails)
        val channelAvatar = bestImageUrl(info.uploaderAvatars)
        val plotText = info.description?.content?.takeIf { it.isNotBlank() }
        val releaseYear = info.uploadDate?.offsetDateTime()?.year
        val runtimeMinutes = (info.duration / 60).toInt().takeIf { it > 0 }
        val creatorActors = info.uploaderName
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf(ActorData(Actor(it, image = channelAvatar))) }
        val related = info.relatedItems
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        val isLive = info.streamType == StreamType.LIVE_STREAM ||
            info.streamType == StreamType.AUDIO_LIVE_STREAM

        return if (isLive) {
            newLiveStreamLoadResponse(info.name, url, url) {
                posterUrl = poster
                backgroundPosterUrl = poster
                plot = plotText
                year = releaseYear
                tags = info.tags?.takeIf { it.isNotEmpty() }
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
                tags = info.tags?.takeIf { it.isNotEmpty() }
                actors = creatorActors
                recommendations = related
            }
        }
    }

    private suspend fun loadPlaylist(url: String): LoadResponse? {
        val info = try {
            PlaylistInfo.getInfo(url)
        } catch (_: Exception) {
            return null
        }

        val episodes = info.relatedItems.mapIndexed { index, item ->
            newEpisode(item.url) {
                name = item.name
                season = 1
                episode = index + 1
                posterUrl = bestImageUrl(item.thumbnails)
                runTime = (item.duration / 60).toInt().takeIf { it > 0 }
                description = item.uploaderName?.takeIf { it.isNotBlank() }
            }
        }

        val poster = bestImageUrl(info.thumbnails)
            ?: episodes.firstOrNull()?.posterUrl
        val banner = bestImageUrl(info.banners) ?: poster

        return newTvSeriesLoadResponse(info.name, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            backgroundPosterUrl = banner
            plot = info.description?.content?.takeIf { it.isNotBlank() }
                ?: info.uploaderName?.let { "Playlist by $it" }
            actors = info.uploaderName
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(ActorData(Actor(it, image = bestImageUrl(info.uploaderAvatars)))) }
        }
    }

    private suspend fun loadChannel(url: String): LoadResponse? {
        val info = try {
            ChannelInfo.getInfo(url)
        } catch (_: Exception) {
            return null
        }

        val videosTab = info.tabs.firstOrNull { tab ->
            tab.contentFilters.contains(ChannelTabs.VIDEOS)
        } ?: info.tabs.firstOrNull()

        val videoItems = if (videosTab != null) {
            try {
                ChannelTabInfo.getInfo(service, videosTab)
                    .relatedItems
                    .filterIsInstance<StreamInfoItem>()
            } catch (_: Exception) {
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
                posterUrl = bestImageUrl(item.thumbnails)
                runTime = (item.duration / 60).toInt().takeIf { it > 0 }
                description = item.textualUploadDate?.takeIf { it.isNotBlank() }
            }
        }

        val avatar = bestImageUrl(info.avatars)
        val banner = bestImageUrl(info.banners) ?: avatar

        return newTvSeriesLoadResponse(info.name, url, TvType.TvSeries, episodes) {
            posterUrl = avatar
            backgroundPosterUrl = banner
            plot = info.description?.takeIf { it.isNotBlank() }
            tags = info.tags?.takeIf { it.isNotEmpty() }
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
        } catch (_: Exception) {
            false
        }

        if (extracted) return true

        // Fallback: inspect public StreamInfo directly for muxed progressive streams or live HLS
        val info = try {
            StreamInfo.getInfo(watchUrl)
        } catch (_: Exception) {
            return false
        }

        var emitted = false

        val hlsUrl = info.hlsUrl
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

        info.videoStreams.forEach { stream ->
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

        info.subtitles.forEach { subtitle ->
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
        val poster = bestImageUrl(thumbnails)

        return when (this) {
            is StreamInfoItem -> {
                val isLive = streamType == StreamType.LIVE_STREAM ||
                    streamType == StreamType.AUDIO_LIVE_STREAM
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
}
