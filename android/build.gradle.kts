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
