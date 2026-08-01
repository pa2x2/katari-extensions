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
        versionCode = 7
        versionName = "2.4.0"
        applicationId = "eu.kanade.tachiyomi.extension.en.novelbuddy"

        manifestPlaceholders += mapOf(
            "appName" to "Katari: NovelBuddy",
            "extClass" to ".NovelBuddyFactory",
            "nsfw" to 1,
        )
    }
}
