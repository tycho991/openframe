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
    }

    lint {
        // Keine API über minSdk 21 ohne Prüfung der Version.
        error += "NewApi"
        warningsAsErrors = false
        abortOnError = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
}
