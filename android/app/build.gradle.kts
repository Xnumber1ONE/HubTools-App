plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The signing key comes from GitHub secrets, so every build is signed with the same key
// and installs over the previous one as an update.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")
val runNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.gamestats.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gamestats.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1000 + runNumber // offset: this repo's build counter restarts at 1, installed apps must not see a downgrade
        versionName = "1.$runNumber"
    }
    signingConfigs {
        if (keystorePath != null) {
            create("hubtools") {
                storeFile = file(keystorePath)
                storeType = "pkcs12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "hubtools"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("hubtools")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    lint { checkReleaseBuilds = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
