package eu.kanade.tachiyomi.extension.all.rezka

import eu.kanade.tachiyomi.source.entry.EntryFilter
import eu.kanade.tachiyomi.source.entry.EntryFilterList
import eu.kanade.tachiyomi.source.entry.filter.requireValidFilters

internal class RezkaSearch(
    private val baseUrl: String,
    private val sectionPath: String,
    private val sectionKey: String,
    private val filterConfig: SectionFilterConfig,
) {
    fun filters(): EntryFilterList = EntryFilterList(
        EntryFilter.Header("Leave search empty to browse. Text search only applies section and genre."),
        BrowseModeFilter(),
        CatalogFilterGroup(filterConfig.genres, sectionKey),
        BestByYearFilterGroup(),
        CollectionFilterGroup(filterConfig.collections, sectionKey),
    )

    fun selection(filters: EntryFilterList): SelectedSearchFilters {
        filters.requireValidFilters()
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

    fun sectionListingUrl(page: Int, filter: String? = null, genreSlug: String? = null): String {
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

    fun bestByYearUrl(page: Int, year: Int): String {
        val basePath = "$baseUrl/$sectionPath/best/$year/"
        return if (page == 1) {
            basePath
        } else {
            "${basePath}page/$page/"
        }
    }

    fun collectionListingUrl(page: Int, collectionSlug: String, filter: String? = null): String {
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

}
