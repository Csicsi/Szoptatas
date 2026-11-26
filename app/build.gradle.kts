plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "hu.csicsi.szoptatas"
    compileSdk = 36

    defaultConfig {
        applicationId = "hu.csicsi.szoptatas"
        minSdk = 26
        targetSdk = 36
        // CI build number, so every release is a newer version (Obtainium / Android updates)
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 100 + build
        versionName = "2.$build"
    }

    // Fixed key so updates install over each other and phone + watch share a signature.
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
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
