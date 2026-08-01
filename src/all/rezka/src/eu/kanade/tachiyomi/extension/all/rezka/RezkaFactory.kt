package eu.kanade.tachiyomi.extension.all.rezka

import eu.kanade.tachiyomi.source.entry.EntrySourceFactory
import eu.kanade.tachiyomi.source.entry.UnifiedSource

class RezkaFactory : EntrySourceFactory {
    override fun createSources(): List<UnifiedSource> {
        return listOf(
            RezkaSource(
                name = "Rezka Films",
                pathSegment = "films",
                sourceId = 500000001L,
            ),
            RezkaSource(
                name = "Rezka Series",
                pathSegment = "series",
                sourceId = 500000002L,
            ),
            RezkaSource(
                name = "Rezka Cartoons",
                pathSegment = "cartoons",
                sourceId = 500000003L,
            ),
            RezkaSource(
                name = "Rezka Anime",
                pathSegment = "anime",
                sourceId = 500000004L,
            ),
        )
    }
}
