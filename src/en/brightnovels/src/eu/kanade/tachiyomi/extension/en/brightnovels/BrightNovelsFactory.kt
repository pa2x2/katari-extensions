package eu.kanade.tachiyomi.extension.en.brightnovels

import eu.kanade.tachiyomi.source.entry.EntrySourceFactory
import eu.kanade.tachiyomi.source.entry.UnifiedSource

class BrightNovelsFactory : EntrySourceFactory {
    override fun createSources(): List<UnifiedSource> = listOf(BrightNovelsSource())
}
