pluginManagement {
    repositories {
        gradlePluginPortal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.testing.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.testing.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "openframe"

include(":core")

// :android needs the Android SDK. Without an SDK, :core and the web UI can still be built and tested.
val androidSdk =
    System.getenv("ANDROID_HOME")?.ifBlank { null } ?: System.getenv("ANDROID_SDK_ROOT")?.ifBlank { null }
        ?: file("local.properties").takeIf { it.exists() }?.readLines()?.firstOrNull { it.startsWith("sdk.dir=") }
if (androidSdk != null) {
    include(":android")
} else {
    logger.warn("No Android SDK found (ANDROID_HOME or sdk.dir in local.properties): :android is skipped.")
}
