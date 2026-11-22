plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "net.broodjeaap.roost"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "net.broodjeaap.roost"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes from the environment (CI secrets, see .github/workflows/build-apk.yml);
    // without ROOST_KEYSTORE_FILE the release APK is built unsigned.
    val keystore = providers.environmentVariable("ROOST_KEYSTORE_FILE").orNull
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("ROOST_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ROOST_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ROOST_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // Whole-app R8. Limiting it with packageScope (androidx/kotlin only) renamed Kotlin classes
            // that OkHttp, left unprocessed, extends: the release build crashed on start.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}