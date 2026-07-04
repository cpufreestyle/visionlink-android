plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.visionlink.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.visionlink.android"
        minSdk = 31
        targetSdk = 35
        versionCode = 10
        versionName = "4.9.1"

        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests.all { test ->
            // 项目路径含中文（导盲），显式指定测试 worker 编码防止类路径乱码
            test.jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
        }
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

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Rokid CXR-L SDK
    implementation("com.rokid.cxr:client-l:1.0.3")

    // HTTP client for API calls (also used for LM Studio connection)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // MediaPipe Tasks Vision: 端侧实时手部关键点 + 物体检测（指向引导模式）
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // Google AI Edge LiteRT-LM for on-device LLM inference
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")
    // GPU backend support
    implementation("com.google.ai.edge.litert:litert-gpu:1.2.0")
    implementation("com.google.android.gms:play-services-tasks:18.1.0")

    // ONNX Runtime (端侧声纹识别推理)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    // RecyclerView (声纹用户列表)
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Multidex
    implementation("androidx.multidex:multidex:2.0.1")

    // Core library desugaring
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}