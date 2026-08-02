package eu.kanade.tachiyomi.extension.all.rezka

import eu.kanade.tachiyomi.extension.BuildConfig
import eu.kanade.tachiyomi.source.entry.EntrySourceFactory
import eu.kanade.tachiyomi.source.entry.UnifiedSource

class RezkaFactory : EntrySourceFactory {
    override fun createSources(): List<UnifiedSource> {
        return listOf(
            RezkaSource(
                name = "Rezka Films",
                pathSegment = "films",
                sourceId = BuildConfig.SOURCE_ID_REZKA_FILMS,
            ),
            RezkaSource(
                name = "Rezka Series",
                pathSegment = "series",
                sourceId = BuildConfig.SOURCE_ID_REZKA_SERIES,
            ),
            RezkaSource(
                name = "Rezka Cartoons",
                pathSegment = "cartoons",
                sourceId = BuildConfig.SOURCE_ID_REZKA_CARTOONS,
            ),
            RezkaSource(
                name = "Rezka Anime",
                pathSegment = "anime",
                sourceId = BuildConfig.SOURCE_ID_REZKA_ANIME,
            ),
        )
    }
}
