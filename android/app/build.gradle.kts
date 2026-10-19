plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.thumbracer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thumbracer"
        minSdk = 29 // low-latency Wi-Fi lock
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // One committed key so every CI build installs over the previous one.
    // shortcut: key is public in the repo, fine for sideloading; make a private key before any store release.
    signingConfigs {
        create("shared") {
            storeFile = file("thumbracer.keystore")
            storePassword = "thumbracer"
            keyAlias = "thumbracer"
            keyPassword = "thumbracer"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("shared") }
        getByName("release") {
            isMinifyEnabled = false // Profile is read by reflection
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
