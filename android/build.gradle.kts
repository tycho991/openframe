import com.android.build.api.variant.AndroidComponentsExtension

// AGP brings its own Kotlin compiler. The version from the catalog has to win,
// otherwise it cannot read the metadata of :core and kotlin-stdlib.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.tycho991.openframe"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.tycho991.openframe"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time from :core on API 21 to 25.
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        // No API above minSdk 21 without checking the version.
        error += "NewApi"
        abortOnError = true
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        // The same test images as in :core.
        getByName("test").resources.srcDir(rootProject.file("core/src/test/resources"))
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}

// The web UI is built before the assets are assembled and placed into the APK under assets/webadmin/.
val webadminDir: Directory = rootProject.layout.projectDirectory.dir("webadmin")

val noNode = "openframe: Node/npm not found. Run \"mise install\" first. Without the web UI no APK is built."

val buildWebadmin by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the web UI with npm (Node from .mise.toml)."
    workingDir = webadminDir.asFile
    inputs.dir(webadminDir.dir("src"))
    inputs.dir(webadminDir.dir("public"))
    inputs.files(
        webadminDir.file("package.json"),
        webadminDir.file("package-lock.json"),
        webadminDir.file("vite.config.ts"),
        webadminDir.file("svelte.config.js"),
        webadminDir.file("index.html"),
        rootProject.layout.projectDirectory.file("api/openapi.yaml"),
    )
    outputs.dir(webadminDir.dir("dist"))
    commandLine(
        "sh",
        "-c",
        "command -v npm >/dev/null 2>&1 || { echo '$noNode' >&2; exit 1; }; npm ci --no-audit --no-fund && npm run build",
    )
}

abstract class CopyWebadmin : DefaultTask() {
    @get:InputDirectory
    abstract val dist: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        fs.sync {
            from(dist)
            into(outputDir.dir("webadmin"))
        }
    }
}

val copyWebadmin by tasks.registering(CopyWebadmin::class) {
    dependsOn(buildWebadmin)
    dist.set(webadminDir.dir("dist"))
}

extensions.getByType(AndroidComponentsExtension::class.java).onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(copyWebadmin, CopyWebadmin::outputDir)
}

// The frame mirrors the design tokens of the web UI as ds_<token> resources (design-system, design.md,
// decisions 2 and 3). Both files are written by hand in a fixed shape, one declaration per line, so regular
// expressions are enough; a declaration in another shape shows up as a missing token, not as silence.
val checkDesignTokens by tasks.registering {
    group = "verification"
    description = "Checks that every ds_* resource equals its token in webadmin/src/styles/tokens.css."
    val tokensCss = webadminDir.file("src/styles/tokens.css").asFile
    val resources = fileTree("src/main/res") { include("values*/*.xml") }
    inputs.file(tokensCss)
    inputs.files(resources)
    val root = rootProject.layout.projectDirectory.asFile
    doLast {
        val tokens = mutableMapOf<String, String>()
        Regex("""^\s*--([a-z0-9-]+):\s*(.+?);\s*$""", RegexOption.MULTILINE)
            .findAll(tokensCss.readText())
            .forEach { tokens.putIfAbsent(it.groupValues[1], it.groupValues[2]) }

        // Colours as #rrggbbaa: CSS writes the alpha last, Android first.
        fun cssColor(hex: String) = if (hex.length == 7) "${hex}ff" else hex

        fun androidColor(hex: String) = if (hex.length == 7) "${hex}ff" else "#${hex.substring(3)}${hex.substring(1, 3)}"

        val problems = mutableListOf<String>()
        val resource = Regex("""<(color|dimen) name="ds_([a-z0-9_]+)">([^<]*)</\1>""")
        for (file in resources.files.sorted()) {
            for (match in resource.findAll(file.readText())) {
                val (kind, name, value) = match.destructured
                val token = name.replace('_', '-')
                val where = file.relativeTo(root)
                val css = tokens[token]
                if (css == null) {
                    problems += "$token: ds_$name in $where has no token --$token in tokens.css"
                    continue
                }
                val same =
                    when (kind) {
                        "color" -> {
                            val dark = Regex("""^light-dark\(\s*(#[0-9a-fA-F]+)\s*,\s*(#[0-9a-fA-F]+)\s*\)$""").find(css)
                            val expected = cssColor((dark?.groupValues?.get(2) ?: css).lowercase())
                            Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$").matches(value) &&
                                androidColor(value.lowercase()) == expected
                        }

                        else -> {
                            val px = Regex("""^([0-9.]+)px$""").find(css)?.groupValues?.get(1)
                            val dp = Regex("""^([0-9.]+)(dp|sp)$""").find(value)?.groupValues?.get(1)
                            px != null && dp != null && px.toFloat() == dp.toFloat()
                        }
                    }
                if (!same) problems += "$token: tokens.css has $css, ds_$name in $where has $value"
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "The frame's design tokens differ from webadmin/src/styles/tokens.css (the dark value of a colour, " +
                    "px as dp or sp):\n" + problems.joinToString("\n") { "  $it" },
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkDesignTokens)
}

tasks.named("preBuild") {
    dependsOn(checkDesignTokens)
}
