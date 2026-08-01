package eu.kanade.tachiyomi.extension.all.rezka

internal data class SeriesInitArgs(
    val postId: Int,
    val translatorId: Int,
    val seasonId: Int,
    val episodeId: Int,
)

internal data class SeasonData(
    val label: String?,
    val seasonNumber: Int,
)

internal data class EpisodeData(
    val sourceIndex: Int,
    val seasonId: Int?,
    val episodeId: Int?,
    val seasonNumber: Int?,
    val seasonLabel: String?,
    val title: String,
    val localEpisodeNumber: Double?,
    val uploadDate: Long,
)

internal data class ScheduledEpisodeDate(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val airDate: Long,
)

internal data class EpisodeArgs(
    val videoUrl: String,
    val translatorId: Int?,
    val seasonId: Int?,
    val episodeId: Int?,
) {
    companion object {
        fun fromUrl(url: String): EpisodeArgs {
            val split = url.split('#', limit = 2)
            val videoUrl = split[0]
            val params = split.getOrNull(1)
                ?.split('&')
                ?.mapNotNull {
                    val pair = it.split('=', limit = 2)
                    if (pair.size == 2) pair[0] to pair[1] else null
                }
                ?.toMap()
                .orEmpty()

            return EpisodeArgs(
                videoUrl = videoUrl,
                translatorId = params["translator"]?.toIntOrNull(),
                seasonId = params["season"]?.toIntOrNull(),
                episodeId = params["episode"]?.toIntOrNull(),
            )
        }
    }
}

internal fun String.toRezkaChildWebUrl(baseUrl: String): String = baseUrl + EpisodeArgs.fromUrl(this).videoUrl

internal data class SelectedSearchFilters(
    val browseMode: BrowseMode,
    val listingMode: ListingModeOption,
    val genre: GenreOption,
    val bestYear: Int,
    val collection: CollectionOption,
    val collectionListingMode: ListingModeOption,
)

internal enum class BrowseMode {
    CATALOG,
    BEST_BY_YEAR,
    COLLECTIONS,
}
