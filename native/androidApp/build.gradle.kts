plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.mini.hub.android"
    compileSdk = 34

    val googleOAuthClientId = providers.gradleProperty("mini.googleOAuthClientId").orNull.orEmpty()
    val isGoogleOAuthPlaceholder = googleOAuthClientId == "1234567890-abcdef.apps.googleusercontent.com"
    val googleOAuthClientPrefix = googleOAuthClientId
        .takeIf { !isGoogleOAuthPlaceholder && it.endsWith(".apps.googleusercontent.com") }
        ?.substringBeforeLast(".apps.googleusercontent.com")
        ?.takeIf { it.isNotBlank() }
    val googleOAuthRedirectScheme = googleOAuthClientPrefix
        ?.let { "com.googleusercontent.apps.$it" }
        ?: "app.mini.hub"

    val microsoftOAuthClientId = providers.gradleProperty("mini.outlookOAuthClientId").orNull.orEmpty()

    defaultConfig {
        applicationId = "app.mini.hub"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["gmailRedirectScheme"] = googleOAuthRedirectScheme
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"${googleOAuthClientId.replace("\"", "\\\"")}\"")
            buildConfigField("String", "MICROSOFT_OAUTH_CLIENT_ID", "\"${microsoftOAuthClientId.replace("\"", "\\\"")}\"")
        }
        getByName("release") {
            buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"${googleOAuthClientId.replace("\"", "\\\"")}\"")
            buildConfigField("String", "MICROSOFT_OAUTH_CLIENT_ID", "\"${microsoftOAuthClientId.replace("\"", "\\\"")}\"")
        }
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.mediapipe.tasks.genai)
    testImplementation(kotlin("test"))
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
