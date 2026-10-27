package eu.kanade.tachiyomi.extension.all.rezka

import android.app.Application
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.entry.ChapterWebViewSource
import eu.kanade.tachiyomi.source.entry.EmptyChapterListSource
import eu.kanade.tachiyomi.source.entry.EntryFilterList
import eu.kanade.tachiyomi.source.entry.EntryHttpSource
import eu.kanade.tachiyomi.source.entry.EntryMedia
import eu.kanade.tachiyomi.source.entry.EntryPageResult
import eu.kanade.tachiyomi.source.entry.EntryRefresh
import eu.kanade.tachiyomi.source.entry.EntryType
import eu.kanade.tachiyomi.source.entry.PlaybackSelection
import eu.kanade.tachiyomi.source.entry.RelatedEntriesSource
import eu.kanade.tachiyomi.source.entry.RelatedEntryGroup
import eu.kanade.tachiyomi.source.entry.SEntry
import eu.kanade.tachiyomi.source.entry.SEntryChapter
import eu.kanade.tachiyomi.source.entry.SourceMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

internal class RezkaSource(
    override val name: String,
    private val pathSegment: String,
    private val sourceId: Long,
) : EntryHttpSource(), ChapterWebViewSource, SourceMetadata, RelatedEntriesSource, EmptyChapterListSource {

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

    private val search by lazy { RezkaSearch(baseUrl, sectionPath, pathSegment, filterConfig) }

    private val json by lazy {
        Json {
            ignoreUnknownKeys = true
        }
    }

    override fun getChapterUrl(chapter: SEntryChapter): String = chapter.url.toRezkaChildWebUrl(baseUrl)

    override suspend fun getPopularContent(page: Int): EntryPageResult<SEntry> {
        return fetchListingPage(search.sectionListingUrl(page, filter = "popular"))
    }

    override suspend fun getSearchContent(
        page: Int,
        query: String,
        filters: EntryFilterList,
    ): EntryPageResult<SEntry> {
        val trimmedQuery = query.trim()
        val selectedFilters = search.selection(filters)

        if (trimmedQuery.isBlank()) {
            val url = when (selectedFilters.browseMode) {
                BrowseMode.CATALOG -> search.sectionListingUrl(
                    page = page,
                    filter = selectedFilters.listingMode.queryValue,
                    genreSlug = selectedFilters.genre.slug,
                )
                BrowseMode.BEST_BY_YEAR -> search.bestByYearUrl(page, selectedFilters.bestYear)
                BrowseMode.COLLECTIONS -> search.collectionListingUrl(
                    page = page,
                    collectionSlug = selectedFilters.collection.slug,
                    filter = selectedFilters.collectionListingMode.queryValue,
                )
            }
            return fetchListingPage(url)
        }

        if (page > 1) return EntryPageResult(emptyList(), false)

        val url = "$baseUrl/search/?do=search&subaction=search&q=${trimmedQuery.encodeForQuery()}"
        val entries = parseListing(fetchDocument(url), baseUrl)
            .filter { matchesSourceSection(it.url) }
            .filter { matchesSelectedGenre(it.url, selectedFilters.genre.slug) }
        return EntryPageResult(entries, false)
    }

    override suspend fun getLatestUpdates(page: Int): EntryPageResult<SEntry> {
        return fetchListingPage(search.sectionListingUrl(page))
    }

    override fun getFilterList(): EntryFilterList = search.filters()

    override suspend fun refresh(
        entry: SEntry,
        existingChapters: List<SEntryChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): EntryRefresh {
        val html = fetchHtml(baseUrl + entry.url)
        val document = html.asDocument(baseUrl + entry.url)
        val hasPlayer = hasPlayer(html)

        val details = if (fetchDetails) parseEntryDetails(document, entry, hasPlayer) else entry
        if (!fetchChapters) return EntryRefresh(entry = details, chapters = existingChapters)

        // Titles the site is still waiting for have nothing to play, which only this check tells from a broken page.
        if (isAwaited(document, hasPlayer)) return EntryRefresh(entry = details, chapters = emptyList())

        val catalog = buildEpisodeCatalog(entry.url, getEpisodeCatalogDocuments(entry.url, document))
        if (catalog.chapters.isEmpty()) {
            return EntryRefresh(entry = details, chapters = listOf(movieChapter(entry.url, releaseDate(document))))
        }
        return EntryRefresh(entry = details, chapters = catalog.chapters, chapterGroups = catalog.groups)
    }

    override suspend fun getRelatedEntries(entry: SEntry): List<RelatedEntryGroup> {
        return listOfNotNull(parseFranchise(fetchDocument(baseUrl + entry.url), baseUrl))
    }

    private suspend fun getEpisodeCatalogDocuments(videoUrl: String, currentDocument: Document): List<Document> {
        val primaryCatalogUrl = primaryTranslatorCatalogUrl(currentDocument, baseUrl)
            ?: return listOf(currentDocument)
        if (primaryCatalogUrl == baseUrl + videoUrl) return listOf(currentDocument)

        return listOf(fetchDocument(primaryCatalogUrl), currentDocument)
    }

    override suspend fun getMedia(
        chapter: SEntryChapter,
        selection: PlaybackSelection,
    ): EntryMedia {
        val args = EpisodeArgs.fromUrl(chapter.url)
        val html = fetchHtml(baseUrl + args.videoUrl)
        val document = html.asDocument(baseUrl + args.videoUrl)
        val init = parsePlayerInit(html, json)
            ?: throw IOException("Rezka has no player for this title yet")

        val translators = parseTranslators(document)
        val dubs = translatorOptions(translators, init)
        val translatorId = (selection.dubKey ?: args.translatorId?.toString())
            ?.takeIf { key -> dubs.any { it.key == key } }
            ?.toInt()
            ?: init.translatorId

        suspend fun descriptorFor(translatorId: Int) = buildPlaybackDescriptor(
            payload = when (init.kind) {
                PlayerKind.SERIES -> seriesPayload(init, args, translatorId)
                PlayerKind.MOVIE -> moviePayload(init, args.videoUrl, translatorId, translators, document)
            },
            dubs = dubs,
            dubKey = translatorId.toString(),
            requestedServerKey = selection.serverKey,
            mediaHeaders = mediaHeaders(),
        )

        // A voiceover chosen for the title may lack this episode or be Premium-only; the page's own voiceover plays then.
        val descriptor = try {
            descriptorFor(translatorId)
        } catch (e: IOException) {
            if (translatorId == init.translatorId) throw e
            descriptorFor(init.translatorId)
        }
        return EntryMedia.Playback(descriptor)
    }

    private suspend fun seriesPayload(init: PlayerInit, args: EpisodeArgs, translatorId: Int): JsonObject {
        val seasonId = args.seasonId ?: init.seasonId
        val episodeId = args.episodeId ?: init.episodeId
        if (
            translatorId == init.translatorId &&
            seasonId == init.seasonId &&
            episodeId == init.episodeId &&
            init.payload != null
        ) {
            return init.payload
        }
        if (seasonId == null || episodeId == null) {
            throw IOException("Rezka episode is missing its season or number")
        }

        val body = FormBody.Builder()
            .add("id", init.postId.toString())
            .add("translator_id", translatorId.toString())
            .add("season", seasonId.toString())
            .add("episode", episodeId.toString())
            .add("action", "get_stream")
            .build()
        return fetchPlayerPayload(args.videoUrl, body)
    }

    private suspend fun moviePayload(
        init: PlayerInit,
        videoUrl: String,
        translatorId: Int,
        translators: List<RezkaTranslator>,
        document: Document,
    ): JsonObject {
        val translator = translators.firstOrNull { it.id == translatorId }
        if ((translatorId == init.translatorId || translator == null) && init.payload != null) {
            return init.payload
        }

        val body = FormBody.Builder()
            .add("id", init.postId.toString())
            .add("translator_id", translatorId.toString())
            .add("is_camrip", translator?.camrip ?: "0")
            .add("is_ads", translator?.ads ?: "0")
            .add("is_director", translator?.director ?: "0")
            .add("favs", document.selectFirst("#ctrl_favs")?.attr("value").orEmpty())
            .add("action", "get_movie")
            .build()
        return fetchPlayerPayload(videoUrl, body)
    }

    /** The player data of another voiceover or episode, which the site loads through `/ajax/get_cdn_series/`. */
    private suspend fun fetchPlayerPayload(videoUrl: String, body: FormBody): JsonObject {
        val responseBody = client.newCall(
            POST(
                url = "$baseUrl/ajax/get_cdn_series/?t=${System.currentTimeMillis()}",
                headers = ajaxHeaders(videoUrl),
                body = body,
            ),
        ).awaitSuccess().use { it.body.string() }
        return parseAjaxPayload(responseBody, json)
    }

    private fun mediaHeaders(): Map<String, String> = mapOf(
        "Referer" to "$baseUrl/",
        "Origin" to baseUrl,
        "User-Agent" to network.defaultUserAgentProvider(),
    )

    private fun ajaxHeaders(videoUrl: String): Headers {
        return headers.newBuilder()
            .set("Referer", baseUrl + videoUrl)
            .set("Origin", baseUrl)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    private fun matchesSourceSection(url: String): Boolean {
        return url.lowercase().startsWith("/$sectionPath/")
    }

    private fun matchesSelectedGenre(url: String, genreSlug: String?): Boolean {
        if (genreSlug == null) return true
        return url.lowercase().startsWith("/$sectionPath/$genreSlug/")
    }

    private suspend fun fetchListingPage(url: String): EntryPageResult<SEntry> {
        val document = fetchDocument(url)
        return EntryPageResult(parseListing(document, baseUrl), hasNextPage(document))
    }

    // Katari calls some capabilities, such as related entries, from the main thread, and a title page is too large to
    // arrive with the headers, so its body is read off the main thread.
    private suspend fun fetchHtml(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(GET(url, headers)).awaitSuccess().use { it.body.string() }
    }

    private suspend fun fetchDocument(url: String): Document = fetchHtml(url).asDocument(url)

    private fun String.asDocument(url: String): Document = Jsoup.parse(this, url)
}
