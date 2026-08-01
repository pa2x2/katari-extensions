package eu.kanade.tachiyomi.extension.en.novelbuddy

import eu.kanade.tachiyomi.source.entry.EntrySourceFactory
import eu.kanade.tachiyomi.source.entry.UnifiedSource

class NovelBuddyFactory : EntrySourceFactory {
    override fun createSources(): List<UnifiedSource> = listOf(NovelBuddySource())
}
