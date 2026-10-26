plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.animalsniffer)
}

kotlin {
    jvmToolchain(17)
}

// :core runs on Android from API 21, with core library desugaring (java.time). Checks every call
// of JDK classes against the API available there, because Android Lint does not see :core.
animalsniffer {
    sourceSets = listOf(project.sourceSets["main"])
}

dependencies {
    signature("com.toasttab.android:gummy-bears-api-21:${libs.versions.gummy.bears.get()}:coreLib2@signature")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.nanohttpd)
    implementation(libs.okhttp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.yaml)
    testImplementation(libs.metadata.extractor)
    testImplementation(libs.twelvemonkeys.webp)
    testImplementation(libs.zxing.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Small enough that an upload of 300 MB gets through only when streamed.
    maxHeapSize = "256m"
    // Contract tests check every response against the API contract.
    val openapi = rootProject.layout.projectDirectory.file("api/openapi.yaml")
    inputs.file(openapi).withPropertyName("openapi")
    systemProperty("openframe.openapi", openapi.asFile.absolutePath)
    // The cases of `Presentation.of` that the web UI's copy of it runs as well.
    val presentationCases = rootProject.layout.projectDirectory.file("api/fixtures/presentation-cases.json")
    inputs.file(presentationCases).withPropertyName("presentationCases")
    systemProperty("openframe.presentationCases", presentationCases.asFile.absolutePath)
    // Lets tests send their own `Host` header, e.g. for invitation links.
    systemProperty("jdk.httpclient.allowRestrictedHeaders", "host")
}

// :core must run on the plain JVM. Platform-dependent things belong behind the
// interfaces in `platform/` and are implemented in :android.
val checkNoAndroidImports by tasks.registering {
    group = "verification"
    description = "Checks that :core contains no android.* or androidx.* imports."
    val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
    inputs.files(sources)
    val root = layout.projectDirectory.asFile
    doLast {
        val pattern = Regex("""^\s*import\s+(androidx?)\.""")
        val violations =
            sources.files.sorted().flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (pattern.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}: ${line.trim()}" else null
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException(
                ":core must not contain Android imports (module boundary, see design.md, decision 1):\n" +
                    violations.joinToString("\n") { "  $it" },
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkNoAndroidImports)
}

// Before compiling, so that the message does not disappear behind "Unresolved reference".
tasks.named("compileKotlin") {
    dependsOn(checkNoAndroidImports)
}

// Test server for the web UI's Playwright tests, see TestServerMain.
tasks.register<JavaExec>("runTestServer") {
    group = "application"
    description = "Starts :core with a fake MediaEngine and the built web UI for the Playwright tests."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.tycho991.openframe.core.devserver.TestServerMain")
    workingDir = rootProject.projectDir
}
