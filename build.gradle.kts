// VisionLink Android - 项目级构建文件
// 要求: Android 13+ (API 33+)

plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
}

// 从 local.properties 读取 API Key（不提交到 git）
val localProps = java.util.Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}
extra["STEPFUN_API_KEY"] = localProps.getProperty("stepfun.api.key", "")
extra["STEPFUN_API_KEY_TEST"] = localProps.getProperty("stepfun.api.key.test", "")

// GitHub Token 用于 CrashReporter 自动上传崩溃日志
extra["GITHUB_REPORT_TOKEN"] = System.getenv("GITHUB_TOKEN") ?: localProps.getProperty("github.report.token", "")

val lmStudioUrl: String = localProps.getProperty("lmstudio.url", "http://127.0.0.1:1234/v1/chat/completions")
extra["LM_STUDIO_URL"] = lmStudioUrl

tasks.register("clean") {
    delete(layout.buildDirectory)
}
