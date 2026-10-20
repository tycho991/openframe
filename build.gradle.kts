plugins {
    alias(libs.plugins.spotless)
}

val ktlintVersion = libs.versions.ktlint.get()

spotless {
    kotlin {
        target("core/src/**/*.kt", "android/src/**/*.kt")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint(ktlintVersion)
    }
}
