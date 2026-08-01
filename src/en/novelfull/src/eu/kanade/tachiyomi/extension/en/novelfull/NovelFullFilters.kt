package eu.kanade.tachiyomi.extension.en.novelfull

import eu.kanade.tachiyomi.source.entry.EntryFilter
import eu.kanade.tachiyomi.source.entry.EntryFilterList

internal data class NovelFullSearchSelection(
    val query: String?,
    val browsePath: String,
)

internal class NovelFullGenreFilter : EntryFilter.Select<String>(
    name = "Genre",
    values = GENRES.map(NovelFullGenre::label).toTypedArray(),
) {
    val selected: NovelFullGenre
        get() = GENRES.getOrElse(state) { GENRES.first() }
}

internal class NovelFullStatusFilter : EntryFilter.Select<String>(
    name = "Status",
    values = STATUSES.map(NovelFullStatus::label).toTypedArray(),
) {
    val selected: NovelFullStatus
        get() = STATUSES.getOrElse(state) { STATUSES.first() }
}

internal fun novelFullFilterList(): EntryFilterList = EntryFilterList(
    EntryFilter.Header("Keyword search ignores genre and status. Genre takes precedence over status."),
    NovelFullGenreFilter(),
    NovelFullStatusFilter(),
)

internal fun EntryFilterList.toNovelFullSearchSelection(query: String): NovelFullSearchSelection {
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
