package eu.kanade.tachiyomi.extension.en.hianime

import eu.kanade.tachiyomi.source.entry.EntryFilter
import eu.kanade.tachiyomi.source.entry.EntryFilterList

internal data class HiAnimeFilterOption(val label: String, val value: String?)

internal data class HiAnimeSearchSelection(val parameters: List<Pair<String, String>>)

internal class HiAnimeTypeFilter : EntryFilter.Select<String>("Type", TYPE_OPTIONS.labels())
internal class HiAnimeStatusFilter : EntryFilter.Select<String>("Status", STATUS_OPTIONS.labels())
internal class HiAnimeRatingFilter : EntryFilter.Select<String>("Age rating", RATING_OPTIONS.labels())
internal class HiAnimeScoreFilter : EntryFilter.Select<String>("Score", SCORE_OPTIONS.labels())
internal class HiAnimeSeasonFilter : EntryFilter.Select<String>("Season", SEASON_OPTIONS.labels())
internal class HiAnimeLanguageFilter : EntryFilter.Select<String>("Sub/Dub", LANGUAGE_OPTIONS.labels())
internal class HiAnimeSortFilter : EntryFilter.Select<String>("Sort by", SORT_OPTIONS.labels())

internal class HiAnimeGenreCheckBox(
    name: String,
    val value: String,
) : EntryFilter.CheckBox(name)

internal class HiAnimeGenreFilter : EntryFilter.Group<HiAnimeGenreCheckBox>(
    name = "Genres",
    state = GENRES.map { (label, value) -> HiAnimeGenreCheckBox(label, value) },
)

internal class HiAnimeReleasePeriodFilter : EntryFilter.Group<EntryFilter<*>>(
    name = "Release period",
    state = listOf(
        HiAnimeSeasonFilter(),
        EntryFilter.Header("Dates: YYYY, YYYY-MM or YYYY-MM-DD. Leave blank for any date."),
        HiAnimeStartDateFilter(),
        HiAnimeEndDateFilter(),
    ),
)

internal class HiAnimeAdvancedFilter : EntryFilter.Group<EntryFilter<*>>(
    name = "Advanced options",
    state = listOf(HiAnimeRatingFilter(), HiAnimeScoreFilter()),
)

internal fun hiAnimeFilterList(): EntryFilterList = EntryFilterList(
    HiAnimeSortFilter(),
    HiAnimeTypeFilter(),
    HiAnimeLanguageFilter(),
    HiAnimeStatusFilter(),
    HiAnimeGenreFilter(),
    HiAnimeReleasePeriodFilter(),
    HiAnimeAdvancedFilter(),
)

internal fun EntryFilterList.toHiAnimeSearchSelection(query: String): HiAnimeSearchSelection {
    val filters = flatMap { filter ->
        when (filter) {
            is HiAnimeReleasePeriodFilter -> filter.state
            is HiAnimeAdvancedFilter -> filter.state
            else -> listOf(filter)
        }
    }
    return HiAnimeSearchSelection(
        parameters = buildList {
            query.trim().ifBlank { null }?.let { add("keyword" to it) }
            filters.selectedValue<HiAnimeTypeFilter>(TYPE_OPTIONS)?.let { add("type" to it) }
            filters.selectedValue<HiAnimeStatusFilter>(STATUS_OPTIONS)?.let { add("status" to it) }
            filters.selectedValue<HiAnimeRatingFilter>(RATING_OPTIONS)?.let { add("rating" to it) }
            filters.selectedValue<HiAnimeScoreFilter>(SCORE_OPTIONS)?.let { add("score" to it) }
            filters.selectedValue<HiAnimeSeasonFilter>(SEASON_OPTIONS)?.let { add("season" to it) }
            filters.selectedValue<HiAnimeLanguageFilter>(LANGUAGE_OPTIONS)?.let { add("language" to it) }
            filters.selectedValue<HiAnimeSortFilter>(SORT_OPTIONS)?.let { add("sort" to it) }
            filters.filterIsInstance<HiAnimeStartDateFilter>()
                .firstOrNull()?.toDateParameters("s")?.let(::addAll)
            filters.filterIsInstance<HiAnimeEndDateFilter>()
                .firstOrNull()?.toDateParameters("e")?.let(::addAll)
            filters.filterIsInstance<HiAnimeGenreFilter>().firstOrNull()?.state
                ?.filter(HiAnimeGenreCheckBox::state)
                ?.forEach { add("genre[]" to it.value) }
        },
    )
}

private inline fun <reified T : EntryFilter.Select<String>> List<EntryFilter<*>>.selectedValue(
    options: List<HiAnimeFilterOption>,
): String? = filterIsInstance<T>().firstOrNull()?.let { options.getOrElse(it.state) { options.first() }.value }

private fun List<HiAnimeFilterOption>.labels(): Array<String> = map(HiAnimeFilterOption::label).toTypedArray()

private val TYPE_OPTIONS = listOf(
    HiAnimeFilterOption("All", null), HiAnimeFilterOption("TV", "tv"),
    HiAnimeFilterOption("Movie", "movie"), HiAnimeFilterOption("OVA", "ova"),
    HiAnimeFilterOption("ONA", "ona"), HiAnimeFilterOption("Special", "special"),
    HiAnimeFilterOption("Music", "music"),
)
private val STATUS_OPTIONS = listOf(
    HiAnimeFilterOption("All", null), HiAnimeFilterOption("Finished Airing", "completed"),
    HiAnimeFilterOption("Currently Airing", "airing"), HiAnimeFilterOption("Not yet aired", "not_yet_aired"),
)
private val RATING_OPTIONS = listOf(
    HiAnimeFilterOption("All", null), HiAnimeFilterOption("G", "g"), HiAnimeFilterOption("PG", "pg"),
    HiAnimeFilterOption("PG-13", "pg_13"), HiAnimeFilterOption("R", "r_17"),
    HiAnimeFilterOption("R+", "r_plus"), HiAnimeFilterOption("Rx", "rx"),
)
private val SCORE_OPTIONS = listOf(HiAnimeFilterOption("All", null)) + listOf(
    "(10) Masterpiece", "(9) Great", "(8) Very Good", "(7) Good", "(6) Fine",
    "(5) Average", "(4) Bad", "(3) Very Bad", "(2) Horrible", "(1) Appalling",
).mapIndexed { index, label -> HiAnimeFilterOption(label, (10 - index).toString()) }
private val SEASON_OPTIONS = listOf(
    HiAnimeFilterOption("All", null), HiAnimeFilterOption("Spring", "spring"),
    HiAnimeFilterOption("Summer", "summer"), HiAnimeFilterOption("Fall", "fall"),
    HiAnimeFilterOption("Winter", "winter"),
)
private val LANGUAGE_OPTIONS = listOf(
    HiAnimeFilterOption("All", null),
    HiAnimeFilterOption("Subtitled (SUB)", "sub"),
    HiAnimeFilterOption("Dubbed (DUB)", "dub"),
)
private val SORT_OPTIONS = listOf(
    HiAnimeFilterOption("Website default", null), HiAnimeFilterOption("Recently Updated", "updated_date"),
    HiAnimeFilterOption("Recently Added", "added_date"), HiAnimeFilterOption("Release date", "release_date"),
    HiAnimeFilterOption("Trending", "trending"), HiAnimeFilterOption("Name A-Z", "title_az"),
    HiAnimeFilterOption("Score", "avg_score"), HiAnimeFilterOption("MAL Score", "mal_score"),
    HiAnimeFilterOption("Most Watched", "most_viewed"), HiAnimeFilterOption("Most Followed", "most_followed"),
    HiAnimeFilterOption("Number of Episodes", "episode_count"),
)

private val GENRES = listOf(
    "Action" to "action", "Action, Adventure, Supernatural" to "action-adventure-supernatural",
    "Adult Cast" to "adult-cast", "Adventure" to "adventure", "Anthropomorphic" to "anthropomorphic",
    "Avant Garde" to "avant-garde", "Award Winning" to "award-winning", "Boys Love" to "boys-love",
    "CGDCT" to "cgdct", "Childcare" to "childcare", "Combat Sports" to "combat-sports",
    "Comedy" to "comedy", "Crossdressing" to "crossdressing", "Delinquents" to "delinquents",
    "Detective" to "detective", "Drama" to "drama",
    "Drama, Action & Adventure, Animation" to "drama-action-adventure-animation", "Ecchi" to "ecchi",
    "Educational" to "educational", "Erotica" to "erotica", "Fantasy" to "fantasy",
    "Gag Humor" to "gag-humor", "Girls Love" to "girls-love", "Gore" to "gore",
    "Gourmet" to "gourmet", "Harem" to "harem", "Hentai" to "hentai",
    "High Stakes Game" to "high-stakes-game", "Historical" to "historical", "Horror" to "horror",
    "Idols (Female)" to "idols-female", "Idols (Male)" to "idols-male", "Isekai" to "isekai",
    "Iyashikei" to "iyashikei", "Josei" to "josei", "Kids" to "kids",
    "Love Polygon" to "love-polygon", "Love Status Quo" to "love-status-quo",
    "Magical Sex Shift" to "magical-sex-shift", "Mahou Shoujo" to "mahou-shoujo",
    "Martial Arts" to "martial-arts", "Mecha" to "mecha", "Medical" to "medical",
    "Military" to "military", "Music" to "music", "Mystery" to "mystery", "Mythology" to "mythology",
    "Organized Crime" to "organized-crime", "Otaku Culture" to "otaku-culture", "Parody" to "parody",
    "Performing Arts" to "performing-arts", "Pets" to "pets", "Psychological" to "psychological",
    "Racing" to "racing", "Reincarnation" to "reincarnation", "Reverse Harem" to "reverse-harem",
    "Romance" to "romance", "Samurai" to "samurai", "School" to "school", "Sci-Fi" to "sci-fi",
    "Seinen" to "seinen", "Shoujo" to "shoujo", "Shounen" to "shounen", "Showbiz" to "showbiz",
    "Slice of Life" to "slice-of-life", "Space" to "space", "Sports" to "sports",
    "Strategy Game" to "strategy-game", "Supernatural" to "supernatural", "Super Power" to "super-power",
    "Survival" to "survival", "Suspense" to "suspense", "Team Sports" to "team-sports",
    "Thriller" to "thriller", "Time Travel" to "time-travel", "Urban Fantasy" to "urban-fantasy",
    "Vampire" to "vampire", "Video Game" to "video-game", "Villainess" to "villainess",
    "Visual Arts" to "visual-arts", "Workplace" to "workplace",
)
