package eu.kanade.tachiyomi.extension.all.rezka

import android.app.Application
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.entry.ChapterWebViewSource
import eu.kanade.tachiyomi.source.entry.EntryFilter
import eu.kanade.tachiyomi.source.entry.EntryFilterList
import eu.kanade.tachiyomi.source.entry.EntryHttpSource
import eu.kanade.tachiyomi.source.entry.EntryMedia
import eu.kanade.tachiyomi.source.entry.EntryPageResult
import eu.kanade.tachiyomi.source.entry.EntryType
import eu.kanade.tachiyomi.source.entry.PlaybackDescriptor
import eu.kanade.tachiyomi.source.entry.PlaybackSelection
import eu.kanade.tachiyomi.source.entry.SEntry
import eu.kanade.tachiyomi.source.entry.SEntryChapter
import eu.kanade.tachiyomi.source.entry.SourceMetadata
import eu.kanade.tachiyomi.source.entry.SubtitleSource
import eu.kanade.tachiyomi.source.entry.VideoPlaybackOption
import eu.kanade.tachiyomi.source.entry.VideoRequest
import eu.kanade.tachiyomi.source.entry.VideoStream
import eu.kanade.tachiyomi.source.entry.VideoStreamType
import eu.kanade.tachiyomi.source.entry.VideoSubtitle
import eu.kanade.tachiyomi.source.entry.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar
import java.util.Locale

internal class RezkaSource(
    override val name: String,
    private val pathSegment: String,
    private val sourceId: Long,
) : EntryHttpSource(), ChapterWebViewSource, SourceMetadata, SubtitleSource {

    override val id: Long = sourceId
    override val lang: String = "ru"
    override val supportsLatest: Boolean = true
    override val supportedEntryTypes: Set<EntryType> = setOf(EntryType.ANIME)

    override val baseUrl = "https://rezka.ag"
    private val sectionPath = when (pathSegment) {
        "anime" -> "animation"
        else -> pathSegment
    }
    private val filterConfig = SECTION_FILTER_CONFIGS.getValue(pathSegment)

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(
            RezkaAnubisInterceptor(
                context = Injekt.get<Application>(),
                cookieManager = network.cookieJar,
                defaultUserAgentProvider = network::defaultUserAgentProvider,
            ),
        )
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun getChapterUrl(chapter: SEntryChapter): String = chapter.url.toRezkaChildWebUrl(baseUrl)

    private val json by lazy {
        Json {
            ignoreUnknownKeys = true
        }
    }

    override suspend fun getPopularContent(page: Int): EntryPageResult<SEntry> {
        return fetchListingPage(sectionListingUrl(page, filter = "popular"))
    }

    override suspend fun getSearchContent(
        page: Int,
        query: String,
        filters: EntryFilterList,
    ): EntryPageResult<SEntry> {
        val trimmedQuery = query.trim()
        val selectedFilters = selectedFilters(filters)

        if (trimmedQuery.isBlank()) {
            val url = when (selectedFilters.browseMode) {
                BrowseMode.CATALOG -> sectionListingUrl(
                    page = page,
                    filter = selectedFilters.listingMode.queryValue,
                    genreSlug = selectedFilters.genre.slug,
                )
                BrowseMode.BEST_BY_YEAR -> bestByYearUrl(page, selectedFilters.bestYear)
                BrowseMode.COLLECTIONS -> collectionListingUrl(
                    page = page,
                    collectionSlug = selectedFilters.collection.slug,
                    filter = selectedFilters.collectionListingMode.queryValue,
                )
            }
            return fetchListingPage(url)
        }

        if (page > 1) return EntryPageResult(emptyList(), false)

        val url = "$baseUrl/search/?do=search&subaction=search&q=${trimmedQuery.encodeForQuery()}"
        return client.newCall(GET(url, headers)).awaitSuccess().use { response ->
            val document = response.asJsoup()
            val entries = parseListing(document)
                .filter { matchesSourceSection(it.url) }
                .filter { matchesSelectedGenre(it.url, selectedFilters.genre.slug) }
            EntryPageResult(entries, false)
        }
    }

    override suspend fun getLatestUpdates(page: Int): EntryPageResult<SEntry> {
        return fetchListingPage(sectionListingUrl(page))
    }

    override fun getFilterList(): EntryFilterList = EntryFilterList(
        EntryFilter.Header("Leave search empty to browse. Text search only applies section and genre."),
        BrowseModeFilter(),
        CatalogFilterGroup(filterConfig.genres),
        BestByYearFilterGroup(),
        CollectionFilterGroup(filterConfig.collections),
    )

    override suspend fun getContentDetails(entry: SEntry): SEntry {
        return client.newCall(GET(baseUrl + entry.url, headers)).awaitSuccess().use { response ->
            val document = response.asJsoup()
            val metadata = metadataRowsByLabel(document)
            entry.copy().apply {
                initialized = true
                title = document.selectFirst("h1[itemprop=name], h1")?.text()?.trim().orEmpty().ifBlank { entry.title }
                description = document.selectFirst(".b-post__description_text .b-post__description_text_inner, .b-post__description_text_inner, .b-post__description_text")
                    ?.text()
                    ?.trim()
                    ?.ifBlank { entry.description }
                thumbnailUrl = document.selectFirst(".b-sidecover img[src], .b-sidecover img[data-src], .b-content__inline_item-cover img[src]")
                    ?.let { imageUrl(it) }
                    ?: entry.thumbnailUrl
                genre = parseGenres(metadata["Жанр"] ?: metadata["Жанры"])
                    ?.split(", ")
                    ?.filter { it.isNotBlank() }
                    ?.takeIf { it.isNotEmpty() }
                    ?: entry.genre
                author = parsePeople(metadata["Режиссер"] ?: metadata["Режиссёр"])
                    ?.ifBlank { entry.author }
                artist = parseJoinedValues(metadata["Студия"] ?: metadata["Студии"])
                    ?.ifBlank { entry.artist }
                status = parseStatus(
                    statusText = document.selectFirst(".b-post__infolast")?.text()?.trim(),
                    fallback = entry.status,
                )
                type = EntryType.ANIME
            }
        }
    }

    override suspend fun getChapterList(entry: SEntry): List<SEntryChapter> {
        return client.newCall(GET(baseUrl + entry.url, headers)).awaitSuccess().use { response ->
            val html = response.body.string()
            val document = response.asJsoup(html)
            val metadata = metadataRowsByLabel(document)
            val episodeCatalogDocuments = getEpisodeCatalogDocuments(entry.url, document)
            val episodeCatalog = mergeEpisodeCatalogs(episodeCatalogDocuments)

            val seasonItems = episodeCatalog.seasons
            val episodeItems = episodeCatalog.episodes
            val scheduleDates = episodeCatalogDocuments
                .flatMap(::parseScheduledEpisodeDates)
                .associate { (it.seasonNumber to it.episodeNumber) to it.airDate }

            if (episodeItems.isEmpty()) {
                val releaseDate = parseReleaseDate(metadata["Дата выхода"])
                return@use listOf(
                    SEntryChapter.create().apply {
                        url = buildEpisodeUrl(
                            videoUrl = entry.url,
                            seasonId = null,
                            episodeId = null,
                        )
                        name = "Movie"
                        dateUpload = releaseDate ?: 0L
                        chapterNumber = 1.0
                    },
                )
            }

            val seasonDataMap = seasonItems
                .mapNotNull { item ->
                    val seasonId = item.attr("data-tab_id").trim().toIntOrNull()
                        ?: item.attr("data-season_id").trim().toIntOrNull()
                    val label = item.text().trim().ifBlank { null }
                    seasonId?.let {
                        it to SeasonData(
                            label = label,
                            seasonNumber = parseSeasonNumber(label) ?: it,
                        )
                    }
                }
                .toMap()

            val episodes = episodeItems.mapIndexed { index, item ->
                val seasonId = item.attr("data-season_id").trim().toIntOrNull()
                val episodeId = item.attr("data-episode_id").trim().toIntOrNull()
                val title = item.text().trim().ifBlank {
                    episodeId?.let { "Episode $it" } ?: "Episode ${index + 1}"
                }
                val label = seasonDataMap[seasonId]?.label
                val seasonNumber = seasonId?.let { seasonDataMap[it]?.seasonNumber ?: it }
                val uploadDate = if (seasonNumber != null && episodeId != null) {
                    scheduleDates[seasonNumber to episodeId] ?: 0L
                } else {
                    0L
                }

                EpisodeData(
                    sourceIndex = index,
                    seasonId = seasonId,
                    episodeId = episodeId,
                    seasonNumber = seasonNumber,
                    seasonLabel = label,
                    title = title,
                    localEpisodeNumber = parseEpisodeNumber(title) ?: episodeId?.toDouble(),
                    uploadDate = uploadDate,
                )
            }
            val chapterNumbers = absoluteEpisodeNumbers(episodes)

            episodes
                .sortedByDescending { episode -> chapterNumbers.getValue(episode.sourceIndex) }
                .map { episode ->
                    SEntryChapter.create().apply {
                        url = buildEpisodeUrl(
                            videoUrl = entry.url,
                            seasonId = episode.seasonId,
                            episodeId = episode.episodeId,
                        )
                        name = listOfNotNull(episode.seasonLabel, episode.title)
                            .joinToString(" - ")
                            .ifBlank { episode.title }
                        dateUpload = episode.uploadDate
                        chapterNumber = chapterNumbers.getValue(episode.sourceIndex)
                    }
                }
        }
    }

    private suspend fun getEpisodeCatalogDocuments(videoUrl: String, currentDocument: Document): List<Document> {
        val primaryCatalogUrl = primaryTranslatorCatalogUrl(currentDocument, baseUrl)
            ?: return listOf(currentDocument)
        if (primaryCatalogUrl == baseUrl + videoUrl) return listOf(currentDocument)

        val primaryDocument = client.newCall(GET(primaryCatalogUrl, headers)).awaitSuccess().use { response ->
            val html = response.body.string()
            response.asJsoup(html)
        }

        return listOf(primaryDocument, currentDocument)
    }

    override suspend fun getMedia(
        chapter: SEntryChapter,
        selection: PlaybackSelection,
    ): EntryMedia {
        val requestedSelection = selection.copy(streamKey = null)
        return EntryMedia.Playback(resolvePlaybackPayload(chapter, requestedSelection))
    }

    override suspend fun getSubtitles(
        chapter: SEntryChapter,
        selection: PlaybackSelection,
    ): List<VideoSubtitle> {
        val requestedSelection = selection.copy(streamKey = null)
        return runCatching {
            resolveSubtitles(chapter, requestedSelection)
        }.getOrElse {
            emptyList()
        }
    }

    private suspend fun resolvePlaybackPayload(
        chapter: SEntryChapter,
        requestedSelection: PlaybackSelection,
    ): PlaybackDescriptor {
        val args = EpisodeArgs.fromUrl(chapter.url)
        return client.newCall(GET(baseUrl + args.videoUrl, headers)).awaitSuccess().use { response ->
            val html = response.body.string()
            val document = response.asJsoup(html)
            val translatorOptions = parseTranslatorOptions(document, html)
            val requestedTranslatorId = requestedSelection.dubKey?.toIntOrNull()
                ?: args.translatorId
                ?: translatorOptions.firstOrNull()?.key?.toIntOrNull()
            val resolvedArgs = args.copy(translatorId = requestedTranslatorId)
            val initCall = extractInitCall(html, document, resolvedArgs.episodeId != null)
            val resolvedPayload = if (resolvedArgs.episodeId != null) {
                getSeriesPayload(resolvedArgs, initCall)
            } else {
                initCall
            }
            val streams = parseStreams(resolvedPayload)

            val sourceQualityOptions = streams.map { (label, _) ->
                VideoPlaybackOption(
                    key = label,
                    label = label,
                )
            }

            val resolvedSourceQualityKey = resolveSourceQualityKey(
                requestedSourceQualityKey = requestedSelection.sourceQualityKey,
                streams = streams,
            )

            val filteredStreams = resolvedSourceQualityKey
                ?.let { sourceQualityKey -> streams.filter { it.first == sourceQualityKey } }
                ?.takeIf { it.isNotEmpty() }
                ?: streams
            val requestHeaders = mapOf(
                "Referer" to "$baseUrl/",
                "Origin" to baseUrl,
                "User-Agent" to network.defaultUserAgentProvider(),
            )

            PlaybackDescriptor(
                selection = PlaybackSelection(
                    dubKey = requestedTranslatorId?.toString(),
                    sourceQualityKey = resolvedSourceQualityKey,
                ),
                dubs = translatorOptions,
                sourceQualities = sourceQualityOptions.distinctBy(VideoPlaybackOption::key),
                streams = filteredStreams.map { (label, url) ->
                    VideoStream(
                        request = VideoRequest(
                            url = url,
                            headers = requestHeaders,
                        ),
                        label = label,
                        key = buildStreamKey(label, url),
                        type = if (url.contains(".m3u8")) {
                            VideoStreamType.HLS
                        } else {
                            VideoStreamType.PROGRESSIVE
                        },
                    )
                },
            )
        }
    }

    private suspend fun resolveSubtitles(
        chapter: SEntryChapter,
        requestedSelection: PlaybackSelection,
    ): List<VideoSubtitle> {
        val args = EpisodeArgs.fromUrl(chapter.url)
        return client.newCall(GET(baseUrl + args.videoUrl, headers)).awaitSuccess().use { response ->
            val html = response.body.string()
            val document = response.asJsoup(html)
            val translatorOptions = parseTranslatorOptions(document, html)
            val requestedTranslatorId = requestedSelection.dubKey?.toIntOrNull()
                ?: args.translatorId
                ?: translatorOptions.firstOrNull()?.key?.toIntOrNull()
            val resolvedArgs = args.copy(translatorId = requestedTranslatorId)
            val initCall = extractInitCall(html, document, resolvedArgs.episodeId != null)
            val resolvedPayload = if (resolvedArgs.episodeId != null) {
                getSeriesPayload(resolvedArgs, initCall)
            } else {
                initCall
            }
            val requestHeaders = mapOf(
                "Referer" to "$baseUrl/",
                "Origin" to baseUrl,
                "User-Agent" to network.defaultUserAgentProvider(),
            )
            parseSubtitles(resolvedPayload, requestHeaders)
        }
    }

    private suspend fun getSeriesPayload(
        args: EpisodeArgs,
        initCallArgs: String,
    ): String {
        val initArgs = extractSeriesInitArgs(initCallArgs)
        val translatorId = args.translatorId ?: initArgs?.translatorId
        val seasonId = args.seasonId ?: initArgs?.seasonId
        val episodeId = args.episodeId

        val fallbackStreams = if (
            initArgs != null &&
            translatorId != null &&
            seasonId != null &&
            episodeId != null &&
            initArgs.translatorId == translatorId &&
            initArgs.seasonId == seasonId &&
            initArgs.episodeId == episodeId
        ) {
            initCallArgs
        } else {
            ""
        }

        val postId = initArgs?.postId ?: return fallbackStreams
        if (translatorId == null || seasonId == null || episodeId == null) {
            return fallbackStreams
        }

        val body = FormBody.Builder()
            .add("id", postId.toString())
            .add("translator_id", translatorId.toString())
            .add("season", seasonId.toString())
            .add("episode", episodeId.toString())
            .add("action", "get_stream")
            .build()

        val responseBody = runCatching {
            client.newCall(
                POST(
                    url = "$baseUrl/ajax/get_cdn_series/",
                    headers = buildAjaxHeaders(args.videoUrl),
                    body = body,
                ),
            ).awaitSuccess().use { it.body.string() }
        }.getOrNull()

        return responseBody
            ?.takeIf(String::isNotBlank)
            ?: fallbackStreams
    }

    private fun parseListing(document: Document): List<SEntry> {
        return document.select(".b-content__inline_items > .b-content__inline_item")
            .mapNotNull(::toEntry)
    }

    private fun toEntry(element: Element): SEntry? {
        val anchor = element.selectFirst(".b-content__inline_item-link > a[href]") ?: return null
        val url = anchor.absUrl("href").takeIf { it.isNotBlank() } ?: anchor.attr("href")
        val relativeUrl = url.toRelativeUrl(baseUrl) ?: return null
        val title = anchor.text().trim().ifBlank { return null }
        val meta = element.selectFirst(".b-content__inline_item-link > div")?.text()?.trim()
        val badge = element.selectFirst(".b-content__inline_item-cover .cat .entity")?.text()?.trim()
        val info = element.selectFirst(".b-content__inline_item-cover .info")?.text()?.trim()
        val thumbnail = element.selectFirst(".b-content__inline_item-cover img[src], .b-content__inline_item-cover img[data-src]")
            ?.let(::imageUrl)

        return SEntry.create().apply {
            this.url = relativeUrl
            this.title = title
            this.description = listOfNotNull(badge, meta, info).joinToString(" | ").ifBlank { null }
            this.thumbnailUrl = thumbnail
            this.initialized = false
            this.type = EntryType.ANIME
        }
    }

    private fun hasNextPage(document: Document): Boolean {
        return document.selectFirst("link[rel=next]") != null ||
            document.selectFirst(".b-navigation a:has(.b-navigation__next)") != null
    }

    private fun metadataRowsByLabel(document: Document): Map<String, Element> {
        return document.select(".b-post__infotable_right_inner table.b-post__info tr")
            .mapNotNull { row ->
                val label = row.selectFirst("td.l > h2")
                    ?.text()
                    ?.trim()
                    ?.removeSuffix(":")
                    ?.ifBlank { null }
                    ?: return@mapNotNull null
                val valueCell = row.select("td").drop(1).firstOrNull() ?: return@mapNotNull null
                label to valueCell
            }
            .toMap()
    }

    private fun matchesSourceSection(url: String): Boolean {
        return url.lowercase().startsWith("/$sectionPath/")
    }

    private fun matchesSelectedGenre(url: String, genreSlug: String?): Boolean {
        if (genreSlug == null) return true
        return url.lowercase().startsWith("/$sectionPath/$genreSlug/")
    }

    private fun selectedFilters(filters: EntryFilterList): SelectedSearchFilters {
        val browseMode = filters.filterIsInstance<BrowseModeFilter>().firstOrNull()?.selected ?: BrowseMode.CATALOG
        val catalogFilterGroup = filters.filterIsInstance<CatalogFilterGroup>().firstOrNull()
        val bestByYearFilterGroup = filters.filterIsInstance<BestByYearFilterGroup>().firstOrNull()
        val collectionFilterGroup = filters.filterIsInstance<CollectionFilterGroup>().firstOrNull()

        return SelectedSearchFilters(
            browseMode = browseMode,
            listingMode = catalogFilterGroup?.selectedListingMode ?: LISTING_MODES.first(),
            genre = catalogFilterGroup?.selectedGenre ?: filterConfig.genres.first(),
            bestYear = bestByYearFilterGroup?.selectedYear ?: DEFAULT_BEST_BROWSE_YEAR,
            collection = collectionFilterGroup?.selectedCollection ?: filterConfig.collections.first(),
            collectionListingMode = collectionFilterGroup?.selectedListingMode ?: LISTING_MODES.first(),
        )
    }

    private fun sectionListingUrl(page: Int, filter: String? = null, genreSlug: String? = null): String {
        val sectionBasePath = buildString {
            append(baseUrl)
            append('/')
            append(sectionPath)
            append('/')
            if (genreSlug != null) {
                append(genreSlug)
                append('/')
            }
        }
        val pagePath = if (page == 1) {
            sectionBasePath
        } else {
            "${sectionBasePath}page/$page/"
        }

        return if (filter == null) {
            pagePath
        } else {
            "$pagePath?filter=$filter"
        }
    }

    private fun bestByYearUrl(page: Int, year: Int): String {
        val basePath = "$baseUrl/$sectionPath/best/$year/"
        return if (page == 1) {
            basePath
        } else {
            "${basePath}page/$page/"
        }
    }

    private fun collectionListingUrl(page: Int, collectionSlug: String, filter: String? = null): String {
        val basePath = "$baseUrl/collections/$collectionSlug/"
        val pagePath = if (page == 1) {
            basePath
        } else {
            "${basePath}page/$page/"
        }

        return if (filter == null) {
            pagePath
        } else {
            "$pagePath?filter=$filter"
        }
    }

    private suspend fun fetchListingPage(url: String): EntryPageResult<SEntry> {
        return client.newCall(GET(url, headers)).awaitSuccess().use { response ->
            val document = response.asJsoup()
            val entries = parseListing(document)
            EntryPageResult(entries, hasNextPage(document))
        }
    }

    private fun imageUrl(image: Element): String? {
        val raw = image.attr("src").ifBlank { image.attr("data-src") }.trim()
        if (raw.isBlank()) return null
        return if (raw.startsWith("//")) "https:$raw" else raw
    }

    private fun parsePeople(cell: Element?): String? {
        if (cell == null) return null
        return cell.select(".person-name-item [itemprop=name], .persons-list-holder a, a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
    }

    private fun parseGenres(cell: Element?): String? {
        if (cell == null) return null
        return cell.select("span[itemprop=genre], a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
    }

    private fun parseJoinedValues(cell: Element?): String? {
        if (cell == null) return null
        val linkedValues = cell.select("a, .persons-list-holder a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (linkedValues.isNotEmpty()) {
            return linkedValues.joinToString(", ")
        }
        return cell.text().trim().ifBlank { null }
    }

    private fun parseYear(cell: Element?): String? {
        if (cell == null) return null
        val linkedYear = cell.selectFirst("a[href*=/year/]")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
        if (linkedYear != null) return linkedYear

        return YEAR_REGEX.find(cell.text())?.value
    }

    private fun parseDuration(cell: Element?): String? {
        if (cell == null) return null
        return cell.selectFirst("[itemprop=duration]")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: cell.text().trim().ifBlank { null }
    }

    private fun parseScheduledEpisodeDates(document: Document): List<ScheduledEpisodeDate> {
        return document.select(".b-post__schedule_table tr")
            .mapNotNull { row ->
                val seasonEpisode = row.selectFirst("td.td-1")
                    ?.text()
                    ?.trim()
                    ?.ifBlank { null }
                    ?.let(::parseScheduleSeasonEpisode)
                    ?: return@mapNotNull null
                val airDate = parseReleaseDate(
                    row.selectFirst("td.td-4")
                        ?.text()
                        ?.trim()
                        ?.ifBlank { null },
                ) ?: return@mapNotNull null
                ScheduledEpisodeDate(
                    seasonNumber = seasonEpisode.first,
                    episodeNumber = seasonEpisode.second,
                    airDate = airDate,
                )
            }
    }

    private fun parseScheduleSeasonEpisode(text: String): Pair<Int, Int>? {
        val match = SCHEDULE_EPISODE_REGEX.find(text.lowercase(Locale.ROOT)) ?: return null
        val seasonNumber = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val episodeNumber = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        return seasonNumber to episodeNumber
    }

    private fun parseSeasonNumber(label: String?): Int? {
        val normalized = label
            ?.lowercase(Locale.ROOT)
            ?.ifBlank { null }
            ?: return null
        return SEASON_NUMBER_REGEX.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseReleaseDate(cell: Element?): Long? {
        return parseReleaseDate(
            cell?.text()
                ?.trim()
                ?.ifBlank { null },
        )
    }

    private fun parseReleaseDate(text: String?): Long? {
        val normalized = text
            ?.lowercase(Locale.ROOT)
            ?.replace('ё', 'е')
            ?.ifBlank { null }
            ?: return null
        val match = RELEASE_DATE_REGEX.find(normalized) ?: return null
        val day = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val month = RU_MONTHS[match.groupValues.getOrNull(2)] ?: return null
        val year = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return null

        return Calendar.getInstance().apply {
            clear()
            set(year, month, day)
        }.timeInMillis
    }

    private fun parseStatus(statusText: String?, fallback: Int): Int {
        val normalized = statusText
            ?.lowercase(Locale.ROOT)
            ?.trim()
            ?.ifBlank { null }
            ?: return fallback

        return when {
            "заверш" in normalized -> SEntry.COMPLETED
            "онгоинг" in normalized || "выходит" in normalized || "выходят" in normalized || "в процессе" in normalized -> SEntry.ONGOING
            "отмен" in normalized || "закрыт" in normalized -> SEntry.CANCELLED
            "перерыв" in normalized || "хиатус" in normalized || "пауза" in normalized -> SEntry.ON_HIATUS
            else -> fallback
        }
    }

    private fun parseTranslatorOptions(document: Document, html: String): List<VideoPlaybackOption> {
        val translators = document.select(".b-translator__item[data-translator_id], [data-translator_id]")
            .mapNotNull { item ->
                val translatorId = item.attr("data-translator_id").trim().toIntOrNull() ?: return@mapNotNull null
                val label = item.text().trim().ifBlank { return@mapNotNull null }
                VideoPlaybackOption(
                    key = translatorId.toString(),
                    label = label,
                )
            }
            .distinctBy(VideoPlaybackOption::key)

        if (translators.isNotEmpty()) return translators

        val inlineTranslatorId = extractInlineInt(html, "initCDN(?:Series|Movies)Events\\((\\d+),\\s*(\\d+)")?.second
            ?: return emptyList()
        return listOf(
            VideoPlaybackOption(
                key = inlineTranslatorId.toString(),
                label = "Default",
            ),
        )
    }

    private fun extractInitCall(rawHtml: String, document: Document, preferSeries: Boolean): String {
        val html = sequenceOf(rawHtml, document.html())
            .map { Parser.unescapeEntities(it, false) }
            .firstNotNullOfOrNull { candidate ->
                extractInitCallFromHtml(candidate, preferSeries)
            }
            ?: error("Rezka player init data not found")
        return html
    }

    private fun extractInitCallFromHtml(html: String, preferSeries: Boolean): String? {
        val regex = if (preferSeries) {
            Regex("initCDNSeriesEvents\\((.*?)\\)", setOf(RegexOption.DOT_MATCHES_ALL))
        } else {
            Regex("initCDNMoviesEvents\\((.*?)\\)", setOf(RegexOption.DOT_MATCHES_ALL))
        }
        return regex.find(html)?.groupValues?.get(1)
            ?: Regex("initCDN(?:Series|Movies)Events\\((.*?)\\)", setOf(RegexOption.DOT_MATCHES_ALL))
                .find(html)
                ?.groupValues
                ?.get(1)
    }

    private fun extractInlineInt(html: String, regex: String): Pair<Int, Int>? {
        val match = Regex(regex).find(html) ?: return null
        val first = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val second = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        return first to second
    }

    private fun extractSeriesInitArgs(initCallArgs: String): SeriesInitArgs? {
        val match = Regex("^\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)")
            .find(initCallArgs)
            ?: return null

        return SeriesInitArgs(
            postId = match.groupValues[1].toInt(),
            translatorId = match.groupValues[2].toInt(),
            seasonId = match.groupValues[3].toInt(),
            episodeId = match.groupValues[4].toInt(),
        )
    }

    private fun parseStreams(payload: String): List<Pair<String, String>> {
        val streamsMatch = Regex("\"(?:streams|url)\":\"(.*?)\"", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(payload)
            ?.groupValues
            ?.get(1)
            ?: return emptyList()

        val normalized = streamsMatch
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("<span class=\"pjs-prem-quality\">", "")
            .replace("<img src=\"https://static.hdrezka.ac/templates/hdrezka/images/prem-icon.svg\" alt=\"\">", "")
            .replace("</span>", "")

        return Regex("\\[(.*?)](.*?)(?=(?:,\\[)|$)")
            .findAll(normalized)
            .mapNotNull { match ->
                val label = match.groupValues[1].trim().ifBlank { return@mapNotNull null }
                val rawUrls = match.groupValues[2].trim()
                val preferredUrl = rawUrls.split(" or ")
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() }
                    ?: return@mapNotNull null
                label to preferredUrl
            }
            .toList()
    }

    private fun parseSubtitles(
        payload: String,
        headers: Map<String, String>,
    ): List<VideoSubtitle> {
        val payloadJson = runCatching {
            json.parseToJsonElement(payload).jsonObject
        }.getOrNull() ?: return emptyList()
        val normalized = payloadJson.stringFieldOrNull("subtitle")
            ?.takeIf(String::isNotBlank)
            ?: return emptyList()
        val subtitleLanguageMap = payloadJson.jsonObjectFieldOrNull("subtitle_lns")
            ?.let(::parseSubtitleLanguages)
            .orEmpty()
        val defaultLanguage = payloadJson.stringFieldOrNull("subtitle_def")

        return Regex("\\[(.*?)](.*?)(?=(?:,\\[)|$)")
            .findAll(normalized)
            .mapNotNull { match ->
                val label = match.groupValues[1].trim().ifBlank { return@mapNotNull null }
                val url = match.groupValues[2].trim().ifBlank { return@mapNotNull null }
                val language = subtitleLanguageMap[label]
                VideoSubtitle(
                    request = VideoRequest(url = url, headers = headers),
                    label = label,
                    language = language,
                    mimeType = when {
                        url.endsWith(".vtt", ignoreCase = true) -> "text/vtt"
                        url.endsWith(".srt", ignoreCase = true) -> "application/x-subrip"
                        else -> null
                    },
                    key = buildSubtitleKey(label, language, url),
                    isDefault = defaultLanguage != null && language == defaultLanguage,
                )
            }
            .toList()
    }

    private fun parseSubtitleLanguages(payload: String): Map<String, String> {
        return Regex("\"(.*?)\":\"(.*?)\"")
            .findAll(payload)
            .mapNotNull { match ->
                val label = match.groupValues[1].trim().ifBlank { return@mapNotNull null }
                val language = match.groupValues[2].trim()
                label to language
            }
            .toMap()
    }

    private fun parseSubtitleLanguages(payload: JsonObject): Map<String, String> {
        return payload.mapNotNull { (label, value) ->
            label.trim().ifBlank { return@mapNotNull null }
                .let { normalizedLabel ->
                    normalizedLabel to value.jsonPrimitive.contentOrNull.orEmpty().trim()
                }
        }.toMap()
    }

    private fun JsonObject.stringFieldOrNull(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return if (primitive.booleanOrNull == false) {
            null
        } else {
            primitive.contentOrNull
                ?.replace("\\/", "/")
                ?.replace("\\\"", "\"")
                ?.takeIf(String::isNotBlank)
        }
    }

    private fun JsonObject.jsonObjectFieldOrNull(key: String): JsonObject? {
        return runCatching { this[key]?.jsonObject }.getOrNull()
    }

    private fun buildAjaxHeaders(videoUrl: String): Headers {
        return headers.newBuilder()
            .set("Referer", baseUrl + videoUrl)
            .set("Origin", baseUrl)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    private fun buildStreamKey(label: String, url: String): String {
        return "$label|$url"
    }

    private fun buildSubtitleKey(label: String, language: String?, url: String): String {
        return listOf(label, language, url).joinToString("|")
    }

    private fun resolveSourceQualityKey(
        requestedSourceQualityKey: String?,
        streams: List<Pair<String, String>>,
    ): String? {
        if (requestedSourceQualityKey == null) {
            return streams.firstOrNull()?.first
        }

        streams.firstOrNull { (label, _) -> label == requestedSourceQualityKey }?.let { (label, _) ->
            return label
        }

        val requestedHeight = parseQualityHeight(requestedSourceQualityKey)
            ?: return streams.firstOrNull()?.first

        val qualityOptions = streams
            .map { (label, _) -> label to parseQualityHeight(label) }
            .filter { (_, height) -> height != null }
            .map { (label, height) -> label to requireNotNull(height) }

        qualityOptions.firstOrNull { (_, height) -> height == requestedHeight }?.let { (label, _) ->
            return label
        }

        qualityOptions
            .filter { (_, height) -> height <= requestedHeight }
            .maxByOrNull { (_, height) -> height }
            ?.let { (label, _) -> return label }

        qualityOptions
            .filter { (_, height) -> height > requestedHeight }
            .minByOrNull { (_, height) -> height }
            ?.let { (label, _) -> return label }

        return streams.firstOrNull()?.first
    }

    private fun parseQualityHeight(label: String): Int? {
        return Regex("(\\d{3,4})\\s*p", RegexOption.IGNORE_CASE)
            .find(label)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun buildEpisodeUrl(
        videoUrl: String,
        seasonId: Int?,
        episodeId: Int?,
    ): String {
        val params = buildList {
            seasonId?.let { add("season=$it") }
            episodeId?.let { add("episode=$it") }
        }
        return if (params.isEmpty()) {
            videoUrl
        } else {
            "$videoUrl#${params.joinToString("&")}"
        }
    }

    private fun parseEpisodeNumber(title: String): Double? {
        val match = Regex("(\\d+(?:[.,]\\d+)?)").find(title)?.groupValues?.get(1) ?: return null
        return match.replace(',', '.').toDoubleOrNull()
    }
}
