plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

@Suppress("UNCHECKED_CAST")
val configureSharedExtensionModule = rootProject.extra["configureSharedExtensionModule"] as (Project) -> Unit

configureSharedExtensionModule(project)

android {
    defaultConfig {
        versionCode = 7
        versionName = "2.5.0"
        applicationId = "eu.kanade.tachiyomi.extension.en.novelfull"

        manifestPlaceholders += mapOf(
            "appName" to "Katari: NovelFull",
            "extClass" to ".NovelFullFactory",
            "nsfw" to 1,
        )
    }
}
