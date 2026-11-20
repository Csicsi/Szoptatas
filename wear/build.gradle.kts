plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // Same applicationId and key as the phone app: required for Data Layer messaging.
    namespace = "hu.csicsi.szoptatas"
    compileSdk = 35

    defaultConfig {
        applicationId = "hu.csicsi.szoptatas"
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

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
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("com.google.guava:listenablefuture:1.0")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
