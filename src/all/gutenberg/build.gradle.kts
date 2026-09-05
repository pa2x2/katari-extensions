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
        applicationId = "eu.kanade.tachiyomi.extension.all.gutenberg"
        manifestPlaceholders += mapOf(
            "appName" to "Katari: Project Gutenberg",
            "extClass" to ".GutenbergFactory",
            "nsfw" to 0,
        )
    }
}
