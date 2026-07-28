import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

// Release signing, read from the git-ignored keystore.properties (mirrors local.properties'
// "never committed" handling) -- absent entirely on a fresh checkout without a keystore, so
// assembleRelease there still succeeds and just produces an unsigned APK like before, rather than
// failing the build for every clone that doesn't have signing material.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

// OneProvider-Freikontingent (item 3): the app-embedded free-tier API key is a real secret --
// never hardcoded here, never committed. Read from local.properties (already per-developer-
// machine and gitignored, same file Android Studio already uses for sdk.dir) as
// "oneproviderFreeTierApiKey=...". Absent/blank on a fresh checkout -> BuildConfig field is an
// empty string, LLMProviderManager's ONEPROVIDER_FREE branch then simply has no key (fails
// "Testen"/generation cleanly with a normal "kein API-Key" error) instead of the build failing.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.diabai"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.diabai"
        // minSdk 26 (Android 8.0) -- broad real-device coverage (e.g. a Xiaomi Pad 6, shipped on
        // Android 13 with a 2-major-upgrade policy, tops out well below API 36). compileSdk/
        // targetSdk stay pinned to the latest (36) -- only the floor below which the app refuses
        // to install was ever the actual compatibility problem.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Baked in at build-configuration time (once per Gradle invocation) -- shown as-is in
        // the "Über GlucoSphere" settings screen so a build can be identified even without checking
        // git/CI, since this project has no CI-injected build metadata of its own.
        buildConfigField(
            "String",
            "BUILD_TIME",
            "\"${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMAN).format(Date())}\"",
        )
        buildConfigField(
            "String",
            "ONEPROVIDER_FREE_TIER_KEY",
            "\"${localProperties.getProperty("oneproviderFreeTierApiKey", "")}\"",
        )
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.litertlm.android)
    implementation(libs.androidx.browser)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}