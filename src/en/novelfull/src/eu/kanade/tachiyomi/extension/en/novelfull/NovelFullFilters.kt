package eu.kanade.tachiyomi.extension.en.novelfull

import eu.kanade.tachiyomi.source.entry.EntryFilter
import eu.kanade.tachiyomi.source.entry.EntryFilterList
import eu.kanade.tachiyomi.source.entry.filter.*

internal data class NovelFullSearchSelection(
    val query: String?,
    val browsePath: String,
)

internal class NovelFullGenreFilter : EntryFilter.Select<String>(
    name = "Genre",
    values = GENRES.map(NovelFullGenre::label).toTypedArray(),
), EntryFilterMetadataProvider {
    override val filterMetadata = EntryFilterMetadata(
        id = "genre", optionIds = GENRES.map { it.path ?: "any" },
        legacyNames = setOf("Genre"), legacyOptionIds = LEGACY_GENRES_IDS, neutralOptionId = "any",
        description = "Keyword search ignores genre and status. Genre takes precedence over status.",
    )
    val selected: NovelFullGenre
        get() = GENRES.getOrElse(state) { GENRES.first() }
}

internal class NovelFullStatusFilter : EntryFilter.Select<String>(
    name = "Status",
    values = STATUSES.map(NovelFullStatus::label).toTypedArray(),
), EntryFilterMetadataProvider {
    override val filterMetadata = EntryFilterMetadata(
        id = "status", optionIds = STATUSES.map { it.path ?: "any" },
        legacyNames = setOf("Status"), legacyOptionIds = LEGACY_STATUSES_IDS, neutralOptionId = "any",
        description = "Keyword search ignores genre and status. Genre takes precedence over status.",
    )
    val selected: NovelFullStatus
        get() = STATUSES.getOrElse(state) { STATUSES.first() }
}

internal fun novelFullFilterList(): EntryFilterList = EntryFilterList(
    EntryFilter.Header("Keyword search ignores genre and status. Genre takes precedence over status."),
    NovelFullGenreFilter(),
    NovelFullStatusFilter(),
)

internal fun EntryFilterList.toNovelFullSearchSelection(query: String): NovelFullSearchSelection {
    requireValidFilters()
    val keyword = query.trim().ifBlank { null }
    val genre = filterIsInstance<NovelFullGenreFilter>().firstOrNull()?.selected ?: GENRES.first()
    val status = filterIsInstance<NovelFullStatusFilter>().firstOrNull()?.selected ?: STATUSES.first()
    return NovelFullSearchSelection(
        query = keyword,
        browsePath = genre.path ?: status.path ?: "/latest-release-novel",
    )
}

internal data class NovelFullGenre(val label: String, val path: String?)

internal data class NovelFullStatus(val label: String, val path: String?)

private val GENRES = listOf(
    NovelFullGenre("All", null),
    NovelFullGenre("Action", "/genre/Action"),
    NovelFullGenre("Adult", "/genre/Adult"),
    NovelFullGenre("Adventure", "/genre/Adventure"),
    NovelFullGenre("Comedy", "/genre/Comedy"),
    NovelFullGenre("Drama", "/genre/Drama"),
    NovelFullGenre("Ecchi", "/genre/Ecchi"),
    NovelFullGenre("Fantasy", "/genre/Fantasy"),
    NovelFullGenre("Gender Bender", "/genre/Gender+Bender"),
    NovelFullGenre("Harem", "/genre/Harem"),
    NovelFullGenre("Historical", "/genre/Historical"),
    NovelFullGenre("Horror", "/genre/Horror"),
    NovelFullGenre("Josei", "/genre/Josei"),
    NovelFullGenre("Lolicon", "/genre/Lolicon"),
    NovelFullGenre("Magical Realism", "/genre/Magical+Realism"),
    NovelFullGenre("Martial Arts", "/genre/Martial+Arts"),
    NovelFullGenre("Mature", "/genre/Mature"),
    NovelFullGenre("Mecha", "/genre/Mecha"),
    NovelFullGenre("Mystery", "/genre/Mystery"),
    NovelFullGenre("Psychological", "/genre/Psychological"),
    NovelFullGenre("Romance", "/genre/Romance"),
    NovelFullGenre("School Life", "/genre/School+Life"),
    NovelFullGenre("Sci-fi", "/genre/Sci-fi"),
    NovelFullGenre("Seinen", "/genre/Seinen"),
    NovelFullGenre("Shoujo", "/genre/Shoujo"),
    NovelFullGenre("Shounen", "/genre/Shounen"),
    NovelFullGenre("Shounen Ai", "/genre/Shounen+Ai"),
    NovelFullGenre("Slice of Life", "/genre/Slice+of+Life"),
    NovelFullGenre("Smut", "/genre/Smut"),
    NovelFullGenre("Sports", "/genre/Sports"),
    NovelFullGenre("Supernatural", "/genre/Supernatural"),
    NovelFullGenre("Tragedy", "/genre/Tragedy"),
    NovelFullGenre("Video Games", "/genre/Video+Games"),
    NovelFullGenre("Wuxia", "/genre/Wuxia"),
    NovelFullGenre("Xianxia", "/genre/Xianxia"),
    NovelFullGenre("Xuanhuan", "/genre/Xuanhuan"),
    NovelFullGenre("Yaoi", "/genre/Yaoi"),
)

private val STATUSES = listOf(
    NovelFullStatus("All", null),
    NovelFullStatus("Ongoing", "/status/Ongoing"),
    NovelFullStatus("Completed", "/completed-novel"),
)

// Published 2.5 option order, independent of the current display order.
private val LEGACY_GENRES_IDS = listOf(
    "any",
    "/genre/Action",
    "/genre/Adult",
    "/genre/Adventure",
    "/genre/Comedy",
    "/genre/Drama",
    "/genre/Ecchi",
    "/genre/Fantasy",
    "/genre/Gender+Bender",
    "/genre/Harem",
    "/genre/Historical",
    "/genre/Horror",
    "/genre/Josei",
    "/genre/Lolicon",
    "/genre/Magical+Realism",
    "/genre/Martial+Arts",
    "/genre/Mature",
    "/genre/Mecha",
    "/genre/Mystery",
    "/genre/Psychological",
    "/genre/Romance",
    "/genre/School+Life",
    "/genre/Sci-fi",
    "/genre/Seinen",
    "/genre/Shoujo",
    "/genre/Shounen",
    "/genre/Shounen+Ai",
    "/genre/Slice+of+Life",
    "/genre/Smut",
    "/genre/Sports",
    "/genre/Supernatural",
    "/genre/Tragedy",
    "/genre/Video+Games",
    "/genre/Wuxia",
    "/genre/Xianxia",
    "/genre/Xuanhuan",
    "/genre/Yaoi",
)

// Published 2.5 option order, independent of the current display order.
private val LEGACY_STATUSES_IDS = listOf(
    "any",
    "/status/Ongoing",
    "/completed-novel",
)
