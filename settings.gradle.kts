pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        if (gradle.startParameter.projectProperties["useMavenLocal"]?.toBoolean() == true) {
            mavenLocal()
        }
        maven(url = "https://www.jitpack.io")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        if (gradle.startParameter.projectProperties["useMavenLocal"]?.toBoolean() == true) {
            mavenLocal()
        }
        maven(url = "https://www.jitpack.io")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "katari-extensions"

file("src")
    .walkTopDown()
    .maxDepth(3)
    .filter { it.name == "build.gradle.kts" }
    .map { it.parentFile.relativeTo(rootDir).invariantSeparatorsPath }
    .sorted()
    .forEach { include(":${it.replace('/', ':')}") }
