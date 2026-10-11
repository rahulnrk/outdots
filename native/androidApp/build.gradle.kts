import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.mini.hub.android"
    compileSdk = 34

    // Public OAuth client IDs (never secrets). Read from -P flags first, then the gitignored
    // native/local.properties, so a routine build cannot silently drop a configured connector.
    val localProps = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    fun clientId(key: String): String =
        (providers.gradleProperty(key).orNull ?: localProps.getProperty(key)).orEmpty().trim()

    val googleOAuthClientId = clientId("mini.googleOAuthClientId")
    val isGoogleOAuthPlaceholder = googleOAuthClientId == "1234567890-abcdef.apps.googleusercontent.com"
    val googleOAuthClientPrefix = googleOAuthClientId
        .takeIf { !isGoogleOAuthPlaceholder && it.endsWith(".apps.googleusercontent.com") }
        ?.substringBeforeLast(".apps.googleusercontent.com")
        ?.takeIf { it.isNotBlank() }
    val googleOAuthRedirectScheme = googleOAuthClientPrefix
        ?.let { "com.googleusercontent.apps.$it" }
        ?: "app.mini.hub"

    val microsoftOAuthClientId = clientId("mini.outlookOAuthClientId")
    val slackClientId = clientId("mini.slackClientId")
    val xClientId = clientId("mini.xClientId")
    fun quoted(value: String) = "\"${value.replace("\"", "\\\"")}\""

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
        configureEach {
            buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", quoted(googleOAuthClientId))
            buildConfigField("String", "MICROSOFT_OAUTH_CLIENT_ID", quoted(microsoftOAuthClientId))
            buildConfigField("String", "SLACK_CLIENT_ID", quoted(slackClientId))
            buildConfigField("String", "X_CLIENT_ID", quoted(xClientId))
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
    sourceSets {
        getByName("debug") {
            val jni = rootProject.file("whatsappRuntime/build/aar-extract/jni")
            if (jni.exists()) jniLibs.srcDir(jni)
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.mediapipe.tasks.genai)
    val whatsappJar = rootProject.file("whatsappRuntime/build/aar-extract/classes.jar")
    if (whatsappJar.exists()) {
        debugImplementation(files(whatsappJar))
    }
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(kotlin("test"))
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
