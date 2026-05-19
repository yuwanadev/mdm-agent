import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.yuwanadev.mdm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yuwanadev.mdm"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.0.5"
    }

    // Load local.properties if it exists
    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { localProperties.load(it) }
    }

    signingConfigs {
        create("release") {
            val envKeystorePath = System.getenv("RELEASE_STORE_FILE")
            if (envKeystorePath != null) {
                storeFile = file(envKeystorePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            } else {
                val keystorePath = localProperties.getProperty("release.keystore")
                if (keystorePath != null) {
                    storeFile = file(keystorePath)
                    storePassword = localProperties.getProperty("release.keystore.password")
                    keyAlias = localProperties.getProperty("release.key.alias")
                    keyPassword = localProperties.getProperty("release.key.password")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // Material Design
    implementation("com.google.android.material:material:1.11.0")

    // DataStore (preferences)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // OkHttp (WebSocket)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // WebRTC for Screen Mirroring (Stream's maintained fork of libwebrtc)
    implementation("io.getstream:stream-webrtc-android:1.3.10")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")
}
