package eu.kanade.tachiyomi.extension.en.readnovelfull

import eu.kanade.tachiyomi.source.entry.EntrySourceFactory
import eu.kanade.tachiyomi.source.entry.UnifiedSource

class ReadNovelFullFactory : EntrySourceFactory {
    override fun createSources(): List<UnifiedSource> = listOf(ReadNovelFullSource())
}
