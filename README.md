# VisionLink Android - AI 助盲智能眼镜

> 基于 Android 的多模态 AI 助盲眼镜系统，支持端侧推理与云端 API 双模式  
> 当前版本: **v5.9.6**

[![Android](https://img.shields.io/badge/Android-13+-green.svg)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-blue.svg)](https://kotlinlang.org/)
[![Version](https://img.shields.io/badge/version-5.9.6-orange.svg)](https://github.com/cpufreestyle/visionlink-android/releases)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## 📋 目录

- [项目简介](#项目简介)
- [功能特性](#功能特性)
- [下载安装](#下载安装)
- [使用说明](#使用说明)
- [技术架构](#技术架构)
- [多模型 API 配置](#多模型-api-配置)
- [崩溃日志自动上报](#崩溃日志自动上报)
- [项目结构](#项目结构)
- [编译构建](#编译构建)
- [故障排除](#故障排除)
- [更新日志](#更新日志)
- [许可证](#许可证)

---

## 🎯 项目简介

**VisionLink Android** 是一套面向视障用户的多模态 AI 助盲眼镜系统。通过手机摄像头捕获图像，利用 AI 大模型进行实时分析，并通过语音播报和智能眼镜 HUD 将结果传达给用户。

### 核心亮点

- ✅ **多引擎支持** — 端侧推理（Gemma 4 E2B）+ 云端 API（StepFun / 自定义 OpenAI 兼容 API）+ 本地推理（LM Studio）
- ✅ **四种辅助模式** — 障碍物检测、文字识别、场景描述、指向引导
- ✅ **声纹识别** — ONNX 端侧声纹识别，自动辨认用户并应用个性化设置
- ✅ **智能眼镜集成** — Rokid CXR-L 眼镜 HUD 显示 + 蓝牙指环遥控
- ✅ **语音控制** — 中文语音命令，声纹门控保护敏感操作
- ✅ **崩溃自动上报** — 崩溃日志自动上传至 GitHub Issues
- ✅ **应用内更新** — 自动检测新版本并下载安装

---

## ✨ 功能特性

### 四种 AI 辅助模式

| 模式 | 功能 | 示例输出 |
|------|------|---------|
| **模式1: 障碍物检测** | 识别前方障碍物并估算距离方向 | "前方两米有台阶，偏左" |
| **模式2: 文字识别** | OCR 提取图片中的中英文文字 | "出口 →" |
| **模式3: 场景描述** | 用自然语言描述当前场景 | "你在一个明亮的室内走廊" |
| **模式4: 指向引导** | 端侧实时手部检测 + 食指指向导航 | "您指向椅子，正前方，大约三步" |

模式4 使用 MediaPipe 端侧实时手部关键点检测 + 物体检测，全离线运行，支持锚点锁定与跨帧跟踪。

### 多 AI 引擎

| 引擎 | 类型 | 说明 |
|------|------|------|
| **StepFun API** | 云端 | 阶跃星辰 step-1v-8k 视觉模型，默认引擎 |
| **自定义 API** | 云端 | 支持 DeepSeek、通义千问、Moonshot、OpenAI 等 OpenAI 兼容 API |
| **LM Studio** | 局域网 | 连接 PC 上运行的 LM Studio，OpenAI 兼容协议 |
| **Edge (Gemma 4)** | 端侧 | Google LiteRT-LM + Gemma 3n E2B，全离线推理 |

### 其他功能

- **声纹识别** — ONNX Runtime 端侧推理，注册用户后自动辨认，应用个性化模式/语言/语速设置
- **语音命令** — 中文语音控制（拍照、切模式、连续检测等），敏感操作声纹门控
- **蓝牙指环** — BLE 指环遥控器，按键拍照/切模式
- **眼镜 HUD** — Rokid CXR-L 眼镜 HUD 显示分析结果
- **连续检测** — 自动循环拍照分析播报，适合行走场景
- **自动更新** — 应用启动时检查 GitHub Release 新版本
- **崩溃上报** — UncaughtExceptionHandler 自动捕获崩溃，下次启动上传 GitHub Issue

---

## 📥 下载安装

### 直接下载 APK

从 GitHub Releases 下载最新版本：

👉 **[v5.9.6 下载](https://github.com/cpufreestyle/visionlink-android/releases/download/v5.9.6/visionlink-android-v5.9.6.apk)**

### 系统要求

- **Android 13+** (API 33+)
- **RAM**: 建议 6GB 以上（端侧推理需要 4GB+）
- **存储**: 约 200MB（APK），端侧模型另需约 4GB
- **摄像头**: 后置摄像头
- **眼镜**: Rokid CXR-L 兼容智能眼镜（可选，需安装 Rokid AI App）

---

## 📖 使用说明

### 首次启动

1. 安装 APK，授予摄像头、麦克风、蓝牙权限
2. 点击 **"Init AI"** 初始化 AI 引擎（默认 StepFun API）
3. 选择模式，点击 **"拍照分析"** 或开启 **"连续检测"**

### 模式切换

| 操作 | 功能 |
|------|------|
| 点击 **"障碍物"** | 切换到障碍物检测模式 |
| 点击 **"文字"** | 切换到文字识别模式 |
| 点击 **"场景"** | 切换到场景描述模式 |
| 点击 **"指向引导"** | 进入/退出指向引导模式 |

### 设置菜单

点击底部 **"设置"** 按钮：

- **语言切换** — 中文 / English
- **语音命令** — 开启/关闭语音控制
- **蓝牙指环** — 扫描/断开 BLE 指环
- **连接眼镜** — 通过 Rokid AI App 授权连接 CXR-L 眼镜
- **模型 API 设置** — 添加/管理自定义 AI API 配置

### 连接 Rokid 眼镜

1. 确保已安装 **Rokid AI App** 并完成眼镜配对
2. 点击 **"眼镜"** 按钮或设置 → 连接眼镜
3. 在 Rokid AI App 中授权
4. 授权成功后，分析结果会同步显示在眼镜 HUD 上

> 授权超时保护为 120 秒，连接超时为 60 秒。如果超时，请重试。

---

## 🏗️ 技术架构

```
┌──────────────────────────────────────────────────────────┐
│                    VisionLink Android v5.3                 │
├──────────────────────────────────────────────────────────┤
│                                                           │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────────┐  │
│  │  UI Layer    │  │  AI Layer     │  │  Audio Layer     │  │
│  │  MainActivity│◄─►│  AIInference  │◄─►│  TTSManager     │  │
│  │  (HUD)       │  │  Manager      │  │  VoiceManager   │  │
│  └──────┬──────┘  └───────┬───────┘  └─────────────────┘  │
│         │                  │                                 │
│  ┌──────┴──────┐   ┌──────┴──────────────────────────┐    │
│  │ Camera Layer│   │        Inference Engines          │    │
│  │ CameraX     │   │  ┌─────────┐ ┌─────────┐        │    │
│  └─────────────┘   │  │StepFun  │ │Custom   │        │    │
│                    │  │API      │ │API      │        │    │
│  ┌─────────────┐   │  └─────────┘ └─────────┘        │    │
│  │ Glasses     │   │  ┌─────────┐ ┌─────────┐        │    │
│  │ CXRManager  │   │  │LM Studio│ │Edge     │        │    │
│  │ (Rokid)     │   │  │(Local)  │ │(Gemma4) │        │    │
│  └─────────────┘   │  └─────────┘ └─────────┘        │    │
│                    └───────────────────────────────────┘    │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────────┐   │
│  │ VoicePrint  │  │ Guide Mode   │  │  BLE Ring       │   │
│  │ (ONNX)      │  │ (MediaPipe)  │  │  BleRingManager │   │
│  └─────────────┘  └──────────────┘  └─────────────────┘   │
│                                                           │
│  ┌─────────────────────────────────────────────────────┐  │
│  │              CrashReporter (auto-report)              │  │
│  └─────────────────────────────────────────────────────┘  │
│                                                           │
└──────────────────────────────────────────────────────────┘
```

### 技术栈

| 模块 | 技术 | 说明 |
|------|------|------|
| **AI 推理** | LiteRT-LM + OkHttp | 端侧 Gemma 4 + 云端 API |
| **手部检测** | MediaPipe Tasks Vision | 端侧实时手部关键点 + 物体检测 |
| **声纹识别** | ONNX Runtime | 端侧声纹特征提取与比对 |
| **摄像头** | CameraX | AndroidX Camera 库 |
| **语音** | Android TTS + SpeechRecognizer | 语音合成 + 语音识别 |
| **眼镜** | Rokid CXR-L SDK | 智能眼镜连接与 HUD 显示 |
| **蓝牙** | BLE | 蓝牙指环遥控器 |
| **崩溃上报** | OkHttp + GitHub Issues API | 自动上传崩溃日志 |
| **语言** | Kotlin | 全 Kotlin 实现 |

---

## 🔌 多模型 API 配置

v5.3.0 新增自定义 OpenAI 兼容 API 配置功能，支持任意遵循 OpenAI Chat Completions 协议的 API。

### 添加配置

1. 进入 **设置 → 模型 API 设置**
2. 点击 **"添加新配置"**
3. 填写以下信息：

| 字段 | 说明 | 示例 |
|------|------|------|
| 配置名称 | 用于识别的名称 | `DeepSeek` |
| API URL | Chat Completions 端点 | `https://api.deepseek.com/v1/chat/completions` |
| API Key | 你的 API 密钥 | `sk-xxxxxxxx` |
| 视觉模型名 | 支持图片输入的模型 | `deepseek-chat` |
| 文本模型名 | 纯文本模型 | `deepseek-chat` |

### 支持的 API 提供商

| 提供商 | API URL | 推荐模型 |
|--------|---------|---------|
| DeepSeek | `https://api.deepseek.com/v1/chat/completions` | `deepseek-chat` |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` | `qwen-vl-max` |
| Moonshot | `https://api.moonshot.cn/v1/chat/completions` | `moonshot-v1-8k-vision-preview` |
| OpenAI | `https://api.openai.com/v1/chat/completions` | `gpt-4o` |
| LM Studio | `http://<PC_IP>:1234/v1/chat/completions` | `local-model` |

### 管理配置

- **切换**: 点击列表中的配置即可切换
- **编辑**: 长按配置 → 编辑
- **删除**: 长按配置 → 删除
- 配置持久化存储，重启后自动恢复上次选中的 API

---

## 📊 崩溃日志自动上报

App 内置 `CrashReporter`，实现全自动的"崩溃-保存-重启-上传"闭环：

1. **崩溃捕获** — `UncaughtExceptionHandler` 捕获原生崩溃，写入本地文件
2. **下次启动上传** — App 重新启动时检测到崩溃日志，自动上传到 GitHub Issue
3. **关键错误上报** — 相机、AI 推理、眼镜连接、连续检测等关键位置的异常自动上报
4. **无 Token 保底** — 即使未配置 GitHub Token，错误日志仍保留在本地文件

### 启用自动上传

在 SharedPreferences 中配置 GitHub Personal Access Token：

```kotlin
val prefs = getSharedPreferences("visionlink", Context.MODE_PRIVATE)
prefs.edit().putString("github_report_token", "ghp_your_token_here").apply()
```

Token 需要 `repo` 权限以创建 Issue。未配置时日志只保留在本地 `crash_logs/` 目录。

---

## 📁 项目结构

```
visionlink-android/
├── app/
│   ├── build.gradle.kts                    # App 构建配置
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── mediapipe/                  # MediaPipe 模型
│       │   └── models/voiceprint/          # 声纹识别 ONNX 模型
│       ├── java/com/visionlink/android/
│       │   ├── ui/
│       │   │   └── MainActivity.kt         # 主界面 + 交互逻辑
│       │   ├── ai/
│       │   │   ├── AIInferenceManager.kt   # AI 推理管理（多引擎）
│       │   │   ├── ModelApiConfig.kt        # 自定义 API 配置模型
│       │   │   ├── ModelApiConfigDialog.kt  # API 配置对话框
│       │   │   └── HandGuideManager.kt     # 指向引导引擎
│       │   ├── camera/
│       │   │   └── CameraManager.kt        # CameraX 摄像头管理
│       │   ├── audio/
│       │   │   ├── TTSManager.kt           # 语音合成
│       │   │   └── VoiceCommandManager.kt  # 语音命令识别
│       │   ├── glasses/
│       │   │   ├── CXRGlassesManager.kt    # Rokid 眼镜管理
│       │   │   └── RokidCxrHelper.kt       # CXR-L SDK 封装
│       │   ├── bluetooth/
│       │   │   └── BleRingManager.kt       # BLE 指环遥控
│       │   ├── voiceprint/
│       │   │   ├── VoicePrintManager.kt    # 声纹识别管理
│       │   │   ├── VoicePrintDialog.kt     # 声纹用户管理界面
│       │   │   └── UserPreferencesDialog.kt # 用户偏好设置
│       │   ├── controller/
│       │   │   ├── ContinuousDetectionController.kt
│       │   │   ├── GuideModeController.kt
│       │   │   └── VoicePrintController.kt
│       │   └── utils/
│       │       ├── CrashReporter.kt        # 崩溃日志自动上报
│       │       ├── AppUpdateChecker.kt     # 应用更新检查
│       │       ├── ApkDownloader.kt        # APK 下载安装
│       │       └── UpdateDialog.kt         # 更新对话框
│       └── res/
│           ├── layout/activity_main.xml     # 主界面布局
│           ├── values/strings.xml           # 字符串资源
│           └── values-zh/strings.xml        # 中文字符串
├── build.gradle.kts                         # 项目级构建配置
└── README.md
```

---

## 🔧 编译构建

### 环境要求

- Android Studio Hedgehog+
- JDK 17
- Android SDK 35 (compileSdk)
- Kotlin 2.0+

### 配置 API Key

在项目根目录创建 `local.properties`：

```properties
stepfun.api.key=your_stepfun_api_key
stepfun.api.key.test=your_stepfun_test_key
lmstudio.url=http://172.16.20.242:1234/v1/chat/completions
```

### 编译

```bash
# Debug 版本
.\gradlew.bat assembleDebug

# Release 版本
.\gradlew.bat assembleRelease
```

APK 输出路径：`app/build/outputs/apk/release/app-release.apk`

---

## 🐛 故障排除

### 拍照时提示需要相机权限

点击拍照时如果相机权限未授予，App 会自动弹出权限请求。授权后会自动启动相机并重试拍照。

### 眼镜授权超时

- 授权超时为 120 秒，连接超时为 60 秒
- 确保已安装 Rokid AI App 并完成眼镜配对
- 授权过程中 App 会跳过 `onResume` 的相机重启，避免干扰
- 如果超时，点击"眼镜"按钮重试

### AI 推理失败

- **StepFun API**: 检查网络连接，确认 API Key 有效
- **自定义 API**: 在设置 → 模型 API 设置中检查 URL 和 Key
- **LM Studio**: 确保手机和电脑在同一网络，LM Studio 已启动并加载模型
- **Edge (Gemma)**: 确认模型文件已下载到 `filesDir/litert_models/` 目录

### TTS 无语音

1. 安装 Google TTS 引擎（Play 商店）
2. 下载中文语音包（设置 → 语言和输入法 → 文字转语音）
3. 重启应用

### 应用崩溃后无日志上传

崩溃日志需要配置 GitHub Token 才能自动上传。未配置时日志保留在本地 `crash_logs/` 目录。配置方法见[崩溃日志自动上报](#崩溃日志自动上报)。

---

## 📝 更新日志

### v5.9.6

- **修复启动闪退**（关键）：AndroidManifest 主题从 AppCompat 改为 MaterialComponents Bridge，修复 MaterialButton + AppCompat 主题不兼容导致的 inflate 崩溃
- 新增完整项目学习教程（`docs/visionlink-tutorial.html`，11 章）

### v5.9.5

- **崩溃诊断增强**：内置 GitHub Token，CrashReporter 自动上传崩溃日志到 GitHub Issues
- **Native 库兼容性**：启用 `extractNativeLibs` + `useLegacyPackaging`，解决 ML Kit native 库加载问题

### v5.9.4

- **修复 v5.9.3 闪退**：回退 YoloDetector 显式 Delegate 设置，恢复 MediaPipe 默认值
- 添加 OkHttp/Okio ProGuard 规则

### v5.9.3

- 扩展 Gemma 4 模型搜索路径（新增 context.filesDir、/sdcard/ 等目录）
- 新增手动选择 .litertlm 模型文件功能
- 修复版本号解析错误（5.9.2 的 versionCode 59 < 61 导致无法检测更新）
- 修复 StepFun API 连接池和超时参数

### v5.9.2

- **底部按钮布局重构**：从 17 个按钮滚动条改为两行固定网格（4+4），无需滑动
- **启动自动初始化 YOLO**：内置模型，无需手动初始化即可直接拍照
- **拍照自动初始化**：点击拍照时自动初始化 YOLO 并执行
- **模式切换实际生效**：切换模式后重置播报指纹，立即使用新模式

### v5.9.1

- 摄像头权限与通知权限分离
- 连续检测播报优化（指纹缩短到 30 字符，间隔从 12s 缩短到 8s）
- 障碍物检测距离估算优化
- 集成 ML Kit Text Recognition，YOLO 引擎下文字识别可离线

### v5.9.0

- **双源自动更新**：同时检测 GitHub 和 Gitee 仓库，并发竞速取最优版本
- 修复历史 release notes 中文乱码问题

### v5.8.0

- **StepFun 模型更新**：step-1-flash/step-1v-8k → step-3.5-flash/step-1o-turbo-vision

### v5.7.0

- 修复 APK 16KB 页面对齐问题（useLegacyPackaging = false）
- 内置默认 StepFun API Key

### v5.6.0

- **模型更新**：Gemma 3n → Gemma 4 E2B-it
- 眼镜授权超时从 120s 缩短到 30s

### v5.5.0

- **YOLO 物体检测引擎**：EfficientDet-Lite0（COCO 80类），约 30ms/帧，全离线
- **AI 引擎切换**：YOLO / Gemma 4 / API 三种引擎一键切换

### v5.4.0

- 合并 PR #4：Debug BroadcastReceiver + LM Studio 本地代理
- 眼镜 HUD 交互：6 个可点击功能按钮
- 删除 4 个未使用的控制器（AIController、GuideModeController、VoicePrintController、ContinuousDetectionController）

### v5.3.0

- **新功能: 多模型 API 配置** — 支持添加任意 OpenAI 兼容 API（DeepSeek、通义千问、Moonshot、OpenAI 等），多配置管理，一键切换，持久化存储
- **修复: HTTP 超时** — OkHttp 添加连接/读取/写入超时，避免 API 调用永久挂起
- **修复: 重试延迟** — API 调用失败重试时增加延迟，避免立即重试加重负载
- **修复: 测试结果中文化** — API 测试返回信息改为中文

### v5.2.0

- **新功能: 崩溃日志自动上传** — 全局 UncaughtExceptionHandler + GitHub Issues API
- **修复: 拍照权限** — 点拍照时自动检查并请求相机权限，授权后自动重试
- **修复: 眼镜授权超时** — 超时从 60s 增加到 120s，收到结果时取消超时任务
- **修复: 眼镜授权回调** — 超时后仍回调 false 避免 UI 卡死
- **修复: 模式显示语言** — 模式标签跟随语言设置
- **清理: 移除未使用的 A2A 初始化代码**

### v5.1.0

- 声纹用户个性化设置（模式、语言、TTS 语速/音调）
- 声纹门控保护敏感操作
- 应用内自动更新（GitHub Release 检测 + APK 下载安装）

### v5.0.0

- 端侧声纹识别（ONNX Runtime）
- 蓝牙指环遥控器
- 连续检测模式

### v4.2.0

- 模式4: 指向引导（MediaPipe 端侧实时手部+物体检测导航）
- Rokid CXR-L 眼镜真实集成

---

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

---

## 🙏 致谢

- **Google LiteRT-LM** — 端侧 LLM 推理框架
- **Google MediaPipe** — 端侧手部关键点与物体检测
- **Rokid** — CXR-L 智能眼镜 SDK
- **ONNX Runtime** — 端侧声纹识别推理
- **StepFun (阶跃星辰)** — 视觉大模型 API

---

## 📧 联系方式

- **Issue Tracker**: [GitHub Issues](https://github.com/cpufreestyle/visionlink-android/issues)
- **Releases**: [GitHub Releases](https://github.com/cpufreestyle/visionlink-android/releases)

---

**⭐ 如果这个项目对你有帮助，请给它一个星标！**
