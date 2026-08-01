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
        versionCode = 5
        versionName = "2.4.0"
        applicationId = "eu.kanade.tachiyomi.extension.en.readnovelfull"

        manifestPlaceholders += mapOf(
            "appName" to "Katari: ReadNovelFull",
            "extClass" to ".ReadNovelFullFactory",
            "nsfw" to 1,
        )
    }
}
