// VisionLink Android - App级构建文件
// 要求: Android 13+ (API 33+)
// 功能: LiteRT-LM + Gemma 4 E2B + CXR-M 眼镜

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.visionlink.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.visionlink.android"
        minSdk = 33  // Android 13+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // LiteRT-LM 需要的配置
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
        dataBinding = true
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
    // Android 核心库
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")

    // CameraX (视觉输入)
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")

    // LiteRT-LM (Gemma 4 E2B 推理)
    // 官方: developers.google.com/litert-lm
    implementation("com.google.ai.edge.litert-lm:litert-lm:1.0.0")
    
    // LiteRT (视觉模型 .tflite)
    implementation("com.google.ai.edge.litert:litert:1.1.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")

    // CXR-M SDK (眼镜连接 - Rokid)
    // 需要手动下载 SDK，放入 libs/ 目录
    // implementation(files("libs/cxr-m-sdk.aar"))

    // 协程 (异步处理)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // JSON 解析
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.json:json:20240303")

    // 测试
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

// 下载 Gemma 4 E2B 模型任务
tasks.register("downloadGemmaModel") {
    doLast {
        println("📥 请手动下载 Gemma 4 E2B .litertlm 模型:")
        println("   1. 访问: https://developers.google.com/litert-lm/docs/get-started")
        println("   2. 下载 gemma-4-e2b-it.litertlm")
        println("   3. 放入: app/src/main/assets/models/")
    }
}

tasks.register("downloadVisionModel") {
    doLast {
        println("📥 请手动下载视觉模型 .tflite:")
        println("   1. 访问: https://www.tensorflow.org/lite/models")
        println("   2. 下载适合的移动视觉模型 (如 MobileNetV3)")
        println("   3. 放入: app/src/main/assets/models/")
    }
}
