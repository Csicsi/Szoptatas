plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "hu.csicsi.szoptatas"
    compileSdk = 35

    defaultConfig {
        applicationId = "hu.csicsi.szoptatas"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    // Fixed key so updates install over each other and phone + watch share a signature.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/szoptatas.jks")
            storePassword = "szoptatas"
            keyAlias = "szoptatas"
            keyPassword = "szoptatas"
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
