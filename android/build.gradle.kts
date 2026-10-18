import com.android.build.api.variant.AndroidComponentsExtension

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
        // java.time aus :core auf API 21 bis 25.
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        // Keine API über minSdk 21 ohne Prüfung der Version.
        error += "NewApi"
        abortOnError = true
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        // Dieselben Testbilder wie in :core.
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

// Die Web-UI wird vor dem Zusammenstellen der Assets gebaut und unter assets/webadmin/ in die APK gelegt.
val webadminDir: Directory = rootProject.layout.projectDirectory.dir("webadmin")

val buildWebadmin by tasks.registering(Exec::class) {
    group = "build"
    description = "Baut die Web-UI mit npm (Node aus .mise.toml)."
    workingDir = webadminDir.asFile
    inputs.dir(webadminDir.dir("src"))
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
        "command -v npm >/dev/null 2>&1 || { echo 'openframe: Node/npm nicht gefunden. Erst \"mise install\" ausführen, dann erneut bauen. Ohne Web-UI wird keine APK gebaut.' >&2; exit 1; }; " +
            "npm ci --no-audit --no-fund && npm run build",
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
