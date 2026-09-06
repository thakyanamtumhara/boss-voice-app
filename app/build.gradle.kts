plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ketu.boss"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ketu.boss"
        minSdk = 26
        // 34, not 35: keeps foreground-service + edge-to-edge behaviour at the
        // Android-14 rules this app is written and tested against.
        targetSdk = 34
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Private bug sink so a fault on the phone is visible from the Mac.
        // Injected at build time and never committed; blank in a plain build,
        // which simply turns reporting off.
        buildConfigField("String", "BUG_URL", "\"${System.getenv("BOSS_BUG_URL") ?: ""}\"")
        buildConfigField("String", "BUG_KEY", "\"${System.getenv("BOSS_BUG_WRITE_KEY") ?: ""}\"")

        // Galaxy S23 is arm64 only. The vosk AAR ships four ABIs (~40 MB of
        // .so); filtering to one keeps the APK about 30 MB smaller.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // Stable committed debug key so every build installs over the last one
        // without an uninstall. Falls back to the ephemeral debug key.
        getByName("debug") {
            val ks = file("debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    androidResources {
        // The 36 MB speech model ships as a zip in assets and is unpacked once
        // on first run. Storing it uncompressed keeps that unpack fast.
        noCompress += "zip"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("com.alphacephei:vosk-android:0.3.75")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
