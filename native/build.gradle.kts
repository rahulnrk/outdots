// Version set chosen for mutual compatibility with Gradle 8.7 and JDK 17:
// Kotlin 1.9.24 + Compose compiler 1.5.14 (the pairing Google documents for 1.9.24), AGP 8.2.1 (supports Gradle 8.2+, and is the newest AGP the Kotlin 1.9.24 KMP plugin is tested against).
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}
