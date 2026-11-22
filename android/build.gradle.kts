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

// The version lives only in version.txt, which only the release pull request changes (app-release, "Single version
// source"). Minor and patch stay below 100, so the version code X*10000 + Y*100 + Z grows with every release.
val appVersion: String = rootProject.file("version.txt").readText().trim()
val appVersionCode: Int =
    Regex("""^(\d+)\.(\d{1,2})\.(\d{1,2})$""").matchEntire(appVersion)?.destructured?.let { (major, minor, patch) ->
        major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
    } ?: throw GradleException("version.txt holds \"$appVersion\"; expected X.Y.Z with minor and patch below 100, e.g. 0.4.1")

android {
    namespace = "io.github.tycho991.openframe"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.tycho991.openframe"
        minSdk = 21
        // The Android 16 orientation opt-out in the manifest ends with 37: ManifestTest fails at that bump until the
        // render size follows the real window (design decision 5 of android-platform-fixes).
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    buildTypes {
        // Unsigned: CI signs a release in a job of its own, apart from the build (release-pipeline, design.md,
        // decisions 2 and 5). No fallback to a local keystore. R8 stays off, kotlinx.serialization would need keep rules.
        getByName("release") {
            signingConfig = null
            isMinifyEnabled = false
        }
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

// The launcher icons are copies of the app icon's source, webadmin/public/favicon.svg (openspec change app-icon,
// design.md, decision 2): the frame's path data and stroke width as they are, the tile and the sun as the paths of
// the SVG's <rect> and <circle>, in the colour resources of the design tokens. Their colours are covered by
// checkDesignTokens and the web UI's appIcon test.
val checkAppIcon by tasks.registering {
    group = "verification"
    description = "Checks that the launcher icons draw the same shapes as webadmin/public/favicon.svg."
    val source = webadminDir.file("public/favicon.svg").asFile
    val legacy = file("src/main/res/mipmap-anydpi/ic_launcher.xml")
    val foreground = file("src/main/res/drawable/ic_launcher_frame.xml")
    inputs.files(source, legacy, foreground)
    val root = rootProject.layout.projectDirectory.asFile
    doLast {
        val svg = source.readText()

        fun element(name: String): Map<String, String> {
            val tag = Regex("""<$name\b[^>]*>""").find(svg)?.value ?: throw GradleException("$source has no <$name>")
            return Regex("""([a-z-]+)="([^"]*)"""").findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }
        }

        fun number(
            attributes: Map<String, String>,
            key: String,
        ) = attributes[key]?.toDoubleOrNull() ?: throw GradleException("$source: $key is missing or not a number")

        fun n(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

        val rect = element("rect")
        val (w, h, rx) = listOf("width", "height", "rx").map { number(rect, it) }
        val tile =
            "M${n(rx)} 0H${n(w - rx)}A${n(rx)} ${n(rx)} 0 0 1 ${n(w)} ${n(rx)}V${n(h - rx)}" +
                "A${n(rx)} ${n(rx)} 0 0 1 ${n(w - rx)} ${n(h)}H${n(rx)}A${n(rx)} ${n(rx)} 0 0 1 0 ${n(h - rx)}" +
                "V${n(rx)}A${n(rx)} ${n(rx)} 0 0 1 ${n(rx)} 0Z"
        val circle = element("circle")
        val (cx, cy, r) = listOf("cx", "cy", "r").map { number(circle, it) }
        val sun =
            "M${n(cx - r)} ${n(cy)}A${n(r)} ${n(r)} 0 1 0 ${n(cx + r)} ${n(cy)}" +
                "A${n(r)} ${n(r)} 0 1 0 ${n(cx - r)} ${n(cy)}Z"
        val path = element("path")
        val frame = path["d"] ?: throw GradleException("$source: <path> has no d")

        // name -> attribute -> expected value
        val shapes =
            mapOf(
                "tile" to mapOf("pathData" to tile, "fillColor" to "@color/ds_bg"),
                "frame" to
                    mapOf(
                        "pathData" to frame,
                        "strokeColor" to "@color/ds_accent",
                        "strokeWidth" to n(number(path, "stroke-width")),
                    ),
                "sun" to mapOf("pathData" to sun, "fillColor" to "@color/ds_icon_sun"),
            )
        val problems = mutableListOf<String>()
        for ((file, names) in listOf(legacy to listOf("tile", "frame", "sun"), foreground to listOf("frame", "sun"))) {
            val where = file.relativeTo(root)
            val paths =
                Regex("""<path\b[^>]*/>""").findAll(file.readText()).associate { match ->
                    val attributes =
                        Regex("""android:([a-zA-Z]+)="([^"]*)"""").findAll(match.value).associate {
                            it.groupValues[1] to it.groupValues[2]
                        }
                    (attributes["name"] ?: "") to attributes
                }
            for (name in names) {
                val actual = paths[name]
                if (actual == null) {
                    problems += "$where has no <path android:name=\"$name\">"
                    continue
                }
                for ((attribute, expected) in shapes.getValue(name)) {
                    if (actual[attribute] != expected) {
                        problems += "$where, $name: android:$attribute is ${actual[attribute]}, expected $expected"
                    }
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "The launcher icons differ from webadmin/public/favicon.svg:\n" + problems.joinToString("\n") { "  $it" },
            )
        }
    }
}

// The APK ships webadmin/public/licenses/third-party-android.txt (visual-design, "Licences of bundled components").
// Every runtime module of the app needs a "Module: group:name" line there, and the desugared JDK library, which
// carries GPL-2.0 with the Classpath Exception, needs its exact version next to the link to that version's source.
abstract class CheckThirdPartyNotices : DefaultTask() {
    @get:Input
    abstract val modules: SetProperty<String>

    @get:Input
    abstract val desugarModule: Property<String>

    @get:Input
    abstract val desugarVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val notices: RegularFileProperty

    @TaskAction
    fun check() {
        val text = notices.get().asFile.readText()
        val listed = Regex("""^Module: (\S+)$""", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toSet()
        val expected = modules.get() + desugarModule.get()
        val problems = mutableListOf<String>()
        (expected - listed).sorted().forEach { problems += "$it: runtime module without a \"Module:\" line" }
        (listed - expected).sorted().forEach { problems += "$it: \"Module:\" line for a module the app does not use" }
        val desugar =
            Regex(
                """^Module: ${Regex.escape(desugarModule.get())}\r?\nVersion: (\S+)\r?\n""" +
                    """Source: https://github\.com/google/desugar_jdk_libs/tree/[0-9a-f]{40}$""",
                RegexOption.MULTILINE,
            ).find(text)
        val version = desugarVersion.get()
        if (desugar?.groupValues?.get(1) != version) {
            problems += "${desugarModule.get()}: needs \"Version: $version\" and the \"Source:\" link to the commit of that version"
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "${notices.get().asFile.name} does not match the app's runtime modules:\n" + problems.joinToString("\n") { "  $it" },
            )
        }
    }
}

val checkThirdPartyNotices by tasks.registering(CheckThirdPartyNotices::class) {
    group = "verification"
    description = "Checks that third-party-android.txt names every runtime module of the app."
    notices.set(webadminDir.file("public/licenses/third-party-android.txt"))
    val desugar =
        libs.desugar.jdk.libs
            .get()
    desugarModule.set("${desugar.group}:${desugar.name}")
    desugarVersion.set(libs.versions.desugar)

    // Both APKs count: the README installs the debug build. Project modules and platforms (BOMs) bring no code of
    // their own.
    fun runtimeModules(configuration: String) =
        configurations.named(configuration).flatMap { it.incoming.resolutionResult.rootComponent }.map { root ->
            val seen = mutableSetOf<ResolvedComponentResult>()
            val queue = ArrayDeque(listOf(root))
            while (queue.isNotEmpty()) {
                val component = queue.removeFirst()
                if (!seen.add(component)) continue
                component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
            }
            seen
                .filter { it.id is ModuleComponentIdentifier }
                .filterNot { component ->
                    component.variants.any { variant ->
                        variant.attributes.keySet().any {
                            it.name == "org.gradle.category" &&
                                variant.attributes.getAttribute(it).toString() in setOf("platform", "enforced-platform")
                        }
                    }
                }.map { (it.id as ModuleComponentIdentifier).moduleIdentifier.toString() }
                .toSet()
        }
    modules.set(runtimeModules("debugRuntimeClasspath").zip(runtimeModules("releaseRuntimeClasspath")) { a, b -> a + b })
}

tasks.named("check") {
    dependsOn(checkDesignTokens, checkAppIcon, checkThirdPartyNotices)
}

tasks.named("preBuild") {
    dependsOn(checkDesignTokens)
}
