package eu.kanade.tachiyomi.extension.en.novelfull

import eu.kanade.tachiyomi.source.entry.EntrySourceFactory
import eu.kanade.tachiyomi.source.entry.UnifiedSource

class NovelFullFactory : EntrySourceFactory {
    override fun createSources(): List<UnifiedSource> = listOf(NovelFullSource())
}
