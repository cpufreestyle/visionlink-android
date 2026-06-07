// VisionLink Android - 项目设置
// 对应 PC 版项目根目录配置

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // LiteRT-LM 可能需要 Google Maven
        maven { url = uri("https://dl.google.com/dl/android/maven2/") }
    }
}

// 项目名称
rootProject.name = "VisionLink-Android"

// 包含 app 模块
include(":app")
