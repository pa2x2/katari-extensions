package eu.kanade.tachiyomi.extension.all.rezka

import eu.kanade.tachiyomi.source.entry.EntryFilter

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
    name = "Режим просмотра",
    values = BROWSE_MODE_OPTIONS.map(BrowseModeOption::label).toTypedArray(),
) {
    val selected: BrowseMode
        get() = BROWSE_MODE_OPTIONS.getOrElse(state) { BROWSE_MODE_OPTIONS.first() }.mode
}

internal class CatalogFilterGroup(
    genres: List<GenreOption>,
) : EntryFilter.Group<EntryFilter<*>>(
    name = "Каталог",
    state = listOf(
        ListingModeFilter(),
        GenreFilter(genres),
    ),
) {
    val selectedListingMode: ListingModeOption
        get() = state.filterIsInstance<ListingModeFilter>().first().selected

    val selectedGenre: GenreOption
        get() = state.filterIsInstance<GenreFilter>().first().selected
}

internal class BestByYearFilterGroup : EntryFilter.Group<EntryFilter<*>>(
    name = "Топ по году",
    state = listOf(YearFilter()),
) {
    val selectedYear: Int
        get() = state.filterIsInstance<YearFilter>().first().selectedYear
}

internal class CollectionFilterGroup(
    collections: List<CollectionOption>,
) : EntryFilter.Group<EntryFilter<*>>(
    name = "Коллекции",
    state = listOf(
        CollectionFilter(collections),
        ListingModeFilter(),
    ),
) {
    val selectedCollection: CollectionOption
        get() = state.filterIsInstance<CollectionFilter>().first().selected

    val selectedListingMode: ListingModeOption
        get() = state.filterIsInstance<ListingModeFilter>().first().selected
}

internal class ListingModeFilter : EntryFilter.Select<String>(
    name = "Подборка",
    values = LISTING_MODES.map(ListingModeOption::label).toTypedArray(),
) {
    val selected: ListingModeOption
        get() = LISTING_MODES.getOrElse(state) { LISTING_MODES.first() }
}

internal class GenreFilter(
    private val genres: List<GenreOption>,
) : EntryFilter.Select<String>(
    name = "Жанр",
    values = genres.map(GenreOption::label).toTypedArray(),
) {
    val selected: GenreOption
        get() = genres.getOrElse(state) { genres.first() }
}

internal class YearFilter : EntryFilter.Text(
    name = "Год",
    state = DEFAULT_BEST_BROWSE_YEAR.toString(),
) {
    val selectedYear: Int
        get() = YEAR_REGEX.find(state)?.value?.toIntOrNull() ?: DEFAULT_BEST_BROWSE_YEAR
}

internal class CollectionFilter(
    private val collections: List<CollectionOption>,
) : EntryFilter.Select<String>(
    name = "Коллекция",
    values = collections.map(CollectionOption::label).toTypedArray(),
) {
    val selected: CollectionOption
        get() = collections.getOrElse(state) { collections.first() }
}
