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

// Build-time libraries with known vulnerabilities (Dependabot alerts): AGP's plugin classpath, its Unified Test
// Platform and Robolectric in the unit tests. None of them ends up in the APK. Every configuration of every project,
// the plugin classpath included, gets at least the patched version; a newer one that a dependency asks for wins.
// Drop an entry once the dependencies that bring it ask for the patched version themselves. An object, not script
// functions: the configuration cache cannot keep a reference to the settings script.
object PatchedVersions {
    fun of(
        group: String,
        name: String,
        version: String,
    ): String? =
        when {
            group == "io.netty" && name.startsWith("netty-") && version.startsWith("4.1.") -> "4.1.137.Final"

            group == "org.bouncycastle" && name.endsWith("-jdk18on") -> "1.85"

            group == "com.google.guava" && name == "guava" -> "33.7.2-" + version.substringAfter('-', "jre")

            group == "com.google.protobuf" && name in setOf("protobuf-java", "protobuf-java-util", "protobuf-kotlin") &&
                version.startsWith("3.") -> "3.25.5"

            group == "org.apache.commons" && name == "commons-lang3" -> "3.18.0"

            group == "org.apache.httpcomponents" && name == "httpclient" -> "4.5.14"

            group == "org.bitbucket.b_c" && name == "jose4j" -> "0.9.6"

            group == "org.jdom" && name == "jdom2" -> "2.0.6.1"

            else -> null
        }

    // Compares the leading numbers: 4.1.93.Final < 4.1.137.Final, 2.0.6 < 2.0.6.1.
    fun isLower(
        version: String,
        than: String,
    ): Boolean {
        fun numbers(v: String) =
            v
                .split('.', '-')
                .map { it.toIntOrNull() }
                .takeWhile { it != null }
                .filterNotNull()
        val a = numbers(version)
        val b = numbers(than)
        a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> return x < y }
        return a.size < b.size
    }

    fun raise(configurations: ConfigurationContainer) =
        configurations.configureEach {
            resolutionStrategy.eachDependency {
                val version = requested.version?.takeUnless { it.isEmpty() || it.any { c -> c in "[](),+" } }
                val patched = version?.let { of(requested.group, requested.name, it) }
                if (version != null && patched != null && isLower(version, patched)) {
                    useVersion(patched)
                    because("known vulnerability, see settings.gradle.kts")
                }
            }
        }
}

gradle.lifecycle.beforeProject {
    PatchedVersions.raise(buildscript.configurations)
    PatchedVersions.raise(configurations)
}
