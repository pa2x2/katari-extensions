package eu.kanade.tachiyomi.extension.all.rezka

import eu.kanade.tachiyomi.source.entry.EntryFilter
import eu.kanade.tachiyomi.source.entry.filter.*

internal data class BrowseModeOption(
    val label: String,
    val mode: BrowseMode,
)

internal data class ListingModeOption(
    val label: String,
    val queryValue: String?,
)

internal data class GenreOption(
    val label: String,
    val slug: String?,
)

internal data class CollectionOption(
    val label: String,
    val slug: String,
)

internal class BrowseModeFilter : EntryFilter.Select<String>(
    "Режим просмотра", BROWSE_MODE_OPTIONS.map(BrowseModeOption::label).toTypedArray(),
), EntryFilterMetadataProvider {
    override val filterMetadata = rezkaChoiceMetadata(
        "browse.mode", name, BROWSE_MODE_OPTIONS.map { it.mode.name.lowercase() },
        listOf("catalog", "best_by_year", "collections"), "catalog",
    )
    val selected: BrowseMode get() = BROWSE_MODE_OPTIONS[state].mode
}

internal class CatalogFilterGroup(genres: List<GenreOption>, section: String) : EntryFilter.Group<EntryFilter<*>>(
    "Каталог", listOf(ListingModeFilter("catalog.listing"), GenreFilter(genres, section)),
), EntryFilterMetadataProvider, EntryFilterGroupSummary {
    override val filterMetadata = EntryFilterMetadata(
        id = "catalog", description = "Используется в режиме «Каталог». Поиск по тексту учитывает только жанр.",
    )
    val selectedListingMode: ListingModeOption get() = state.filterIsInstance<ListingModeFilter>().first().selected
    val selectedGenre: GenreOption get() = state.filterIsInstance<GenreFilter>().first().selected
    override fun selectionSummary(values: List<EntryFilter<*>>): String? = rezkaGroupSummary(values)
}

internal class BestByYearFilterGroup : EntryFilter.Group<EntryFilter<*>>(
    "Топ по году", listOf(YearFilter()),
), EntryFilterMetadataProvider, EntryFilterGroupSummary {
    override val filterMetadata = EntryFilterMetadata(
        id = "best", description = "Используется только в режиме «Топ по году» без поискового текста.",
    )
    val selectedYear: Int get() = state.filterIsInstance<YearFilter>().first().selectedYear
    override fun selectionSummary(values: List<EntryFilter<*>>): String? = rezkaGroupSummary(values)
}

internal class CollectionFilterGroup(collections: List<CollectionOption>, section: String) : EntryFilter.Group<EntryFilter<*>>(
    "Коллекции", listOf(CollectionFilter(collections, section), ListingModeFilter("collection.listing")),
), EntryFilterMetadataProvider, EntryFilterGroupSummary {
    override val filterMetadata = EntryFilterMetadata(
        id = "collection", description = "Используется только в режиме «Коллекции» без поискового текста.",
    )
    val selectedCollection: CollectionOption get() = state.filterIsInstance<CollectionFilter>().first().selected
    val selectedListingMode: ListingModeOption get() = state.filterIsInstance<ListingModeFilter>().first().selected
    override fun selectionSummary(values: List<EntryFilter<*>>): String? = rezkaGroupSummary(values)
}

internal class ListingModeFilter(id: String) : EntryFilter.Select<String>(
    "Подборка", LISTING_MODES.map(ListingModeOption::label).toTypedArray(),
), EntryFilterMetadataProvider {
    override val filterMetadata = rezkaChoiceMetadata(
        id, name, LISTING_MODES.map { it.queryValue ?: "latest" },
        listOf("latest", "popular", "soon", "watching"), "latest",
    )
    val selected: ListingModeOption get() = LISTING_MODES[state]
}

internal class GenreFilter(private val genres: List<GenreOption>, section: String) : EntryFilter.Select<String>(
    "Жанр", genres.map(GenreOption::label).toTypedArray(),
), EntryFilterMetadataProvider {
    override val filterMetadata = rezkaChoiceMetadata(
        "catalog.genre", name, genres.map { it.slug ?: "any" }, LEGACY_GENRE_IDS.getValue(section), "any",
    )
    val selected: GenreOption get() = genres[state]
}

internal class YearFilter : EntryDateFilter(
    name = "Год",
    filterMetadata = EntryFilterMetadata(
        id = "best.year", legacyNames = setOf("Год"),
        description = "Год в формате ГГГГ. Пустое поле означает текущий год.",
    ),
    initialValue = EntryPartialDate(DEFAULT_BEST_BROWSE_YEAR),
    allowedPrecisions = setOf(EntryDatePrecision.YEAR),
) {
    val selectedYear: Int get() = dateValue?.year ?: DEFAULT_BEST_BROWSE_YEAR
}

internal class CollectionFilter(private val collections: List<CollectionOption>, section: String) : EntryFilter.Select<String>(
    "Коллекция", collections.map(CollectionOption::label).toTypedArray(),
), EntryFilterMetadataProvider {
    override val filterMetadata = rezkaChoiceMetadata(
        "collection.choice", name, collections.map { it.slug }, LEGACY_COLLECTION_IDS.getValue(section),
    )
    val selected: CollectionOption get() = collections[state]
}

private fun rezkaGroupSummary(values: List<EntryFilter<*>>): String? = values.mapNotNull { value ->
    when (value) {
        is EntryFilter.Select<*> -> value.values.getOrNull(value.state)?.toString()
        is EntryFilter.Text -> value.state.trim().ifBlank { null }
        else -> null
    }
}.joinToString().ifBlank { null }
