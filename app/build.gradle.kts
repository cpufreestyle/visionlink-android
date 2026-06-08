plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.visionlink.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.visionlink.android"
        minSdk = 33
        targetSdk = 35
        versionCode = 4
        versionName = "4.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Enable AICore (requires Android 14+)
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    buildFeatures {
        viewBinding = true
        dataBinding = false // Disable to avoid annotation processor issues
    }
    
    packaging {
        resources {
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/NOTICE"
        }
    }
}

dependencies {
    // Android core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    
    // CameraX
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")
    implementation("androidx.camera:camera-image-analysis:1.4.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    
    // AI Inference - LiteRT-LM (Gemma 4 E2B)
    implementation("com.google.ai.edge.litert-lm:litert-lm:0.2.0")
    
    // AI Core (Gemini Nano) - Samsung S24/S25, Pixel 8+
    // TODO: Uncomment when AICore SDK is publicly available
    // implementation("com.google.ai.edge.aicore:aicore:0.1.0")
    // implementation("com.google.android.gms:play-services-base:18.4.0")
    
    // TTS
    implementation("android.speech.tts:TextToSpeech:1.0")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    
    // Multidex (for large apps)
    implementation("androidx.multidex:multidex:2.0.1")
    
    // Core library desugaring (for Java 8+ API on older Android)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
}

// AICore SDK Setup (Manual)
// 1. Download AICore SDK from: https://developers.google.com/ai/core
// 2. Copy .aar file to app/libs/
// 3. Uncomment AICore dependency above
// 4. Sync project and rebuild
