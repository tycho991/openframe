plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.animalsniffer)
}

kotlin {
    jvmToolchain(17)
}

// :core läuft auf Android ab API 21, mit Core Library Desugaring (java.time). Prüft jeden Aufruf
// von JDK-Klassen gegen die dort verfügbare API, weil Android Lint :core nicht sieht.
animalsniffer {
    sourceSets = listOf(project.sourceSets["main"])
}

dependencies {
    signature("com.toasttab.android:gummy-bears-api-21:${libs.versions.gummy.bears.get()}:coreLib2@signature")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.nanohttpd)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.yaml)
    testImplementation(libs.metadata.extractor)
    testImplementation(libs.twelvemonkeys.webp)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Klein genug, dass ein Upload von 300 MB nur gestreamt durchgeht.
    maxHeapSize = "256m"
    // Contract-Tests prüfen jede Antwort gegen den API-Vertrag.
    val openapi = rootProject.layout.projectDirectory.file("api/openapi.yaml")
    inputs.file(openapi).withPropertyName("openapi")
    systemProperty("openframe.openapi", openapi.asFile.absolutePath)
}

// :core muss auf der reinen JVM laufen. Plattformabhängiges gehört hinter die
// Schnittstellen in `platform/` und wird in :android implementiert.
val checkNoAndroidImports by tasks.registering {
    group = "verification"
    description = "Prüft, dass :core keine android.*- oder androidx.*-Importe enthält."
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
                ":core darf keine Android-Importe enthalten (Modulgrenze, siehe design.md, Entscheidung 1):\n" +
                    violations.joinToString("\n") { "  $it" },
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkNoAndroidImports)
}

// Vor dem Kompilieren, damit die Meldung nicht hinter "Unresolved reference" verschwindet.
tasks.named("compileKotlin") {
    dependsOn(checkNoAndroidImports)
}

// Testserver für die Playwright-Tests der Web-UI, siehe TestServerMain.
tasks.register<JavaExec>("runTestServer") {
    group = "application"
    description = "Startet :core mit Fake-MediaEngine und der gebauten Web-UI für die Playwright-Tests."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.tycho991.openframe.core.devserver.TestServerMain")
    workingDir = rootProject.projectDir
}
