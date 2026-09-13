plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

@Suppress("UNCHECKED_CAST")
val configureSharedExtensionModule = rootProject.extra["configureSharedExtensionModule"] as (Project) -> Unit

configureSharedExtensionModule(project)

android {
    defaultConfig {
        versionCode = 1
        versionName = "2.6.0"
        applicationId = "eu.kanade.tachiyomi.extension.en.brightnovels"

        manifestPlaceholders += mapOf(
            "appName" to "Katari: Bright Novels",
            "extClass" to ".BrightNovelsFactory",
            "nsfw" to 1,
        )
    }
}
