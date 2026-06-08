// VisionLink Android - App build file v3.0
// Target: Samsung Galaxy S24 series (Android 13+)
// AI: AICore (Gemini Nano) / LiteRT-LM (Gemma 4 E2B) / Cloud fallback

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.visionlink.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.visionlink.android"
        minSdk = 33  // Android 13+ required for CameraX + LiteRT-LM
        targetSdk = 35
        versionCode = 3
        versionName = "3.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes.addAll(listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/NOTICE.txt",
                "META-INF/LICENSE.txt"
            ))
        }
    }
}

dependencies {
    // Android core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")

    // CameraX (visual input)
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")

    // ========== AI Inference Engines ==========

    // 1. AICore (Gemini Nano) — Samsung Galaxy S24 (Android 14+)
    //    Built into Google Play Services, no manual download needed
    //    API: com.google.android.ai.aicore (Google AI Core services)
    //    Models: Gemini Nano 1 (1.8B params), Nano 2 (3.25B params)
    //    Docs: https://ai.google.dev/aicore
    //    Note: Requires Google AI Core services installed on device
    implementation("com.google.android.gms:play-services-base:18.4.0")

    // 2. LiteRT-LM (Gemma 4 E2B) — Android 13+ (universal)
    //    Official: https://developers.google.com/litert-lm
    implementation("com.google.ai.edge.litert-lm:litert-lm:0.2.0")

    // 3. LiteRT (optional, for vision models)
    implementation("com.google.ai.edge.litert:litert:1.1.0")

    // CXR-M SDK (Rokid glasses — place .aar in libs/)
    // implementation(files("libs/cxr-m-sdk.aar"))

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // JSON
    implementation("com.google.code.gson:gson:2.11.0")

    // Tests
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
