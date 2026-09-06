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
        versionCode = 20
        versionName = "2.6.0"
        applicationId = "eu.kanade.tachiyomi.extension.all.rezka"

        manifestPlaceholders += mapOf(
            "appName" to "Katari: Rezka",
            "extClass" to ".RezkaFactory",
            "nsfw" to 0,
        )
    }
}
