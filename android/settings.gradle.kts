pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor and its own dependencies are published through JitPack only.
        maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\.TeamNewPipe.*") } }
    }
}
rootProject.name = "Spotter"
include(":app")
