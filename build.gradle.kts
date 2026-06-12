// VisionLink Android - 项目级构建文件
// 要求: Android 13+ (API 33+)

plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
}

tasks.register("clean") {
    delete(layout.buildDirectory)
}