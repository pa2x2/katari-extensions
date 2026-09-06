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
        versionCode = 10
        versionName = "2.6.0"
        applicationId = "eu.kanade.tachiyomi.extension.en.novelbuddy"

        manifestPlaceholders += mapOf(
            "appName" to "Katari: NovelBuddy",
            "extClass" to ".NovelBuddyFactory",
            "nsfw" to 1,
        )
    }
}
