package eu.kanade.tachiyomi.extension.en.hianime

import eu.kanade.tachiyomi.source.entry.EntrySourceFactory
import eu.kanade.tachiyomi.source.entry.UnifiedSource

class HiAnimeFactory : EntrySourceFactory {
    override fun createSources(): List<UnifiedSource> = listOf(HiAnimeSource())
}
