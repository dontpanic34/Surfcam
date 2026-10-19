plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.surfcam.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.surfcam.app"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.2.0"

        // Le modèle d'analyse n'est fourni que pour les téléphones 64 bits (le Pixel en est un).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Clé de debug versionnée : chaque APK produit par la CI a la même signature,
    // ce qui permet de mettre à jour l'app sur le téléphone sans la désinstaller.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}
