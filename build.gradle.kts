// VisionLink Android - 项目级构建文件
// 要求: Android 13+ (API 33+)

plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
}

// 从 local.properties 读取 API Key（不提交到 git）
val localProps = java.util.Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}
ext val MOONSHOT_API_KEY: String = localProps.getProperty("moonshot.api.key", "")
ext val MOONSHOT_API_KEY_TEST: String = localProps.getProperty("moonshot.api.key.test", "")

val lmStudioUrl: String = localProps.getProperty("lmstudio.url", "http://172.16.20.242:1234/v1/chat/completions")
ext val LM_STUDIO_URL: String = lmStudioUrl

tasks.register("clean") {
    delete(layout.buildDirectory)
}