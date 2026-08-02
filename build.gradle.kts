import com.android.build.api.dsl.ApplicationExtension
import groovy.json.JsonSlurper
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val useMavenLocal = providers.gradleProperty("useMavenLocal").orNull?.toBoolean() == true
val resolvedKatariSourceApiVersion = if (useMavenLocal) {
    "local-SNAPSHOT"
} else {
    providers.gradleProperty("katariSourceApiVersion")
        .orElse("local-SNAPSHOT")
        .get()
}

val configureSharedExtensionModule = { project: Project ->
    val libs = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
    val metadata = JsonSlurper().parse(project.file("repo-metadata.json")) as Map<*, *>
    val sourceIds = (metadata["sources"] as? List<*>)
        ?.map { source ->
            source as? Map<*, *> ?: error("Invalid source metadata in ${project.path}")
            val key = source["key"] as? String ?: error("Missing source key in ${project.path}")
            require(Regex("[a-z][a-z0-9_]*").matches(key)) { "Invalid source key $key in ${project.path}" }
            val rawId = source["id"] as? Number ?: error("Invalid source ID for $key in ${project.path}")
            val id = rawId.toString().toLongOrNull()?.takeIf { it > 0 }
                ?: error("Invalid source ID for $key in ${project.path}")
            "SOURCE_ID_${key.uppercase()}" to id
        }
        ?.takeIf { it.isNotEmpty() }
        ?: error("Missing sources in ${project.path}/repo-metadata.json")
    check(sourceIds.map { it.first }.distinct().size == sourceIds.size) {
        "Source keys must produce unique BuildConfig fields in ${project.path}"
    }

    project.extensions.configure<ApplicationExtension> {
        namespace = "eu.kanade.tachiyomi.extension"
        compileSdk = 36

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        defaultConfig {
            minSdk = 26
            targetSdk = 36
            sourceIds.forEach { (field, id) ->
                buildConfigField("long", field, "${id}L")
            }
        }

        buildFeatures {
            buildConfig = true
        }

        sourceSets {
            getByName("main") {
                manifest.srcFile("AndroidManifest.xml")
                java.setSrcDirs(listOf("src"))
                kotlin.setSrcDirs(listOf("src"))
                res.setSrcDirs(listOf("res"))
            }
        }

        buildTypes {
            getByName("debug") {
                isMinifyEnabled = false
            }
            getByName("release") {
                isMinifyEnabled = false
            }
        }

        packaging {
            resources {
                excludes += "kotlin-tooling-metadata.json"
            }
        }
    }

    project.extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    project.dependencies.add(
        "compileOnly",
        "com.github.pa2x2.katari:entry-source-api:$resolvedKatariSourceApiVersion",
    )
    project.dependencies.add("compileOnly", libs.findLibrary("jspecify").get().get())
}

extra["configureSharedExtensionModule"] = configureSharedExtensionModule
extra["katariSourceApiVersion"] = resolvedKatariSourceApiVersion
