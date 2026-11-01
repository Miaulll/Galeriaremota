plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.granlogia.boveda"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.granlogia.boveda"
        minSdk = 23
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.2-universal"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
