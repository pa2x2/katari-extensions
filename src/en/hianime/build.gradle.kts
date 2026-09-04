plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

@Suppress("UNCHECKED_CAST")
val configureSharedExtensionModule = rootProject.extra["configureSharedExtensionModule"] as (Project) -> Unit

configureSharedExtensionModule(project)

android {
    defaultConfig {
        versionCode = 1
        versionName = "2.5.0"
        applicationId = "eu.kanade.tachiyomi.extension.en.hianime"

        manifestPlaceholders += mapOf(
            "appName" to "Katari: HiAnime",
            "extClass" to ".HiAnimeFactory",
            "nsfw" to 1,
        )
    }
}
