import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val cellTrackerSigningFile = rootProject.file("celltracker-signing.properties")
val cellTrackerSigning = Properties().apply {
    if (cellTrackerSigningFile.exists()) cellTrackerSigningFile.inputStream().use { load(it) }
}

android {
    namespace = "com.example.celltracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.celltracker"
        minSdk = 29
        targetSdk = 34
        versionCode = 217
        versionName = "1.2.40"
    }

    // Stable internal-test signing for in-place APK upgrades.
    // If celltracker-signing.properties is supplied, it takes precedence. Otherwise the
    // bundled internal-test keystore is used so GitHub Actions does not generate a new
    // random debug certificate on every runner. Do not use the bundled key for Play release.
    signingConfigs {
        create("celltrackerStable") {
            if (cellTrackerSigningFile.exists()) {
                storeFile = rootProject.file(cellTrackerSigning.getProperty("storeFile"))
                storePassword = cellTrackerSigning.getProperty("storePassword")
                keyAlias = cellTrackerSigning.getProperty("keyAlias")
                keyPassword = cellTrackerSigning.getProperty("keyPassword")
            } else {
                storeFile = rootProject.file("keystore/celltracker-dev.jks")
                storePassword = "celltracker123"
                keyAlias = "celltracker"
                keyPassword = "celltracker123"
            }
        }
    }

    buildTypes {
        val stable = signingConfigs.getByName("celltrackerStable")
        getByName("debug").signingConfig = stable
        getByName("release").signingConfig = stable
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
