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
        versionCode = 598
        versionName = "5.9.8"

        multiDexEnabled = true

        // API Key 通过 BuildConfig 注入（不硬编码在源码中）
        buildConfigField("String", "STEPFUN_API_KEY", "\"${rootProject.ext["STEPFUN_API_KEY"]}\"")
        buildConfigField("String", "STEPFUN_API_KEY_TEST", "\"${rootProject.ext["STEPFUN_API_KEY_TEST"]}\"")
        buildConfigField("String", "LM_STUDIO_URL", "\"${rootProject.ext["LM_STUDIO_URL"]}\"")
        // GitHub Token 用于 CrashReporter 自动上传崩溃日志到 GitHub Issues
        buildConfigField("String", "GITHUB_REPORT_TOKEN", "\"${rootProject.ext["GITHUB_REPORT_TOKEN"]}\"")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("KEYSTORE_PATH") ?: ""
            val storePass = System.getenv("KEYSTORE_PASSWORD") ?: ""
            val keyAlias = System.getenv("KEY_ALIAS") ?: ""
            val keyPass = System.getenv("KEY_PASSWORD") ?: ""
            if (storeFilePath.isNotEmpty()) {
                storeFile = file(storeFilePath)
                storePassword = storePass
                this.keyAlias = keyAlias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 优先使用 release 签名，未配置时回退 debug
            signingConfig = if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        buildConfig = true
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
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Android core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    // WorkManager：大模型前台服务下载，App 被杀也不中断
    // 2.11.x 要求 AGP 8.6+，当前 AGP 8.5.0 最高支持 2.10.x
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    // CameraX
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Rokid CXR-L SDK (CXRLink + AuthorizationHelper)
    implementation("com.rokid.cxr:client-l:1.0.3")

    // HTTP client for API calls (also used for LM Studio connection)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // MediaPipe Tasks Vision: 端侧实时手部关键点 + 物体检测（指向引导模式）
    implementation("com.google.mediapipe:tasks-vision:0.10.21")

    // ML Kit Text Recognition: 端侧离线 OCR（模式2 文字识别）
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Google AI Edge LiteRT-LM for on-device LLM inference
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")
    // GPU backend support
    implementation("com.google.ai.edge.litert:litert-gpu:1.2.0")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")

    // ONNX Runtime (端侧声纹识别推理)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")

    // RecyclerView (声纹用户列表)
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // Multidex
    implementation("androidx.multidex:multidex:2.0.1")

    // Core library desugaring
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}