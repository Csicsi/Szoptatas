plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // Same applicationId and key as the phone app: required for Data Layer messaging.
    namespace = "hu.csicsi.szoptatas"
    compileSdk = 36

    defaultConfig {
        applicationId = "hu.csicsi.szoptatas"
        minSdk = 30
        targetSdk = 35
        // CI build number, so every release is a newer version (Obtainium / Android updates)
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 100_000 + build // own range: Play needs unique codes across tracks
        versionName = "2.$build"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/szoptatas.jks")
            storePassword = "szoptatas"
            keyAlias = "szoptatas"
            keyPassword = "szoptatas"
        }
        // Google Play upload key: only available in CI (GitHub secrets), never in the repo
        val uploadStore = System.getenv("UPLOAD_KEYSTORE")
        if (uploadStore != null) {
            create("release") {
                storeFile = file(uploadStore)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = "upload"
                keyPassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
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
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("com.google.guava:listenablefuture:1.0")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
