plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain(17)

    // Fast host target for the shared-core test suite.
    jvm()

    androidTarget()

    // iOS targets need macOS/Xcode. On other hosts, declaring them makes Kotlin/Native add an ivy
    // toolchain repository (rejected by FAIL_ON_PROJECT_REPOS) and download a toolchain it cannot use.
    // They are therefore declared only on macOS or with -Pmini.enableIos=true. Never built on Windows.
    val enableIos = System.getProperty("os.name").lowercase().contains("mac") ||
        (findProperty("mini.enableIos") as String?) == "true"
    if (enableIos) {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(libs.sqlite.jdbc)
        }
        androidMain.dependencies {
            implementation(libs.sqlcipher.android)
            implementation(libs.androidx.sqlite)
        }
    }
}

android {
    namespace = "app.mini.hub.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
