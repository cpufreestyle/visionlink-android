# VisionLink Android - AI 助盲眼镜

> 智能助盲眼镜 Android 实现，支持语音控制、声纹识别、实时场景感知  
> 云端 AI (StepFun) + 端侧声纹识别

[![Android](https://img.shields.io/badge/Android-12+-green.svg)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-blue.svg)](https://kotlinlang.org/)
[![StepFun](https://img.shields.io/badge/StepFun-API-orange.svg)](https://platform.stepfun.com)
[![Version](https://img.shields.io/badge/Version-5.1.0-blue.svg)](https://github.com/cpufreestyle/visionlink-android/releases/tag/v5.1.0)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## 📋 目录

- [项目简介](#项目简介)
- [功能特性](#功能特性)
- [技术架构](#技术架构)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [语音命令](#语音命令)
- [声纹识别](#声纹识别)
- [开发文档](#开发文档)
- [故障排除](#故障排除)
- [贡献指南](#贡献指南)
- [许可证](#许可证)

---

## 🎯 项目简介

**VisionLink Android** 是一款帮助视障人士的 AI 眼镜应用，通过语音交互和实时视觉感知，提供独立出行能力。

### 核心亮点

- ✅ **语音控制**: 10+ 语音命令，支持模糊意图识别 (NLU)
- ✅ **声纹识别**: 本地 ONNX 声纹识别，个性化设置 (语速/音调/语言)
- ✅ **云端 AI**: StepFun API (阶跃星辰) 提供场景理解和图像识别
- ✅ **眼镜集成**: 支持 Rokid CXR-L 智能眼镜
- ✅ **多模式**: 避障、文字阅读、场景描述、连续检测
- ✅ **隐私保护**: 声纹数据本地存储，不上传云端

### 版本历史

| 版本 | 日期 | 主要变更 |
|------|------|---------|
| v5.1.0 | 2024-07 | 切换 AI API 从 Moonshot 到 StepFun |
| v5.0.0 | 2024-06 | 语音控制 v5.0，声纹识别，架构重构 |
| v4.0.0 | 2024-05 | 集成 Rokid 眼镜支持 |
| v3.0.0 | 2024-04 | 添加连续检测和 AI 初始化 |
| v2.0.0 | 2024-03 | 添加文字阅读和场景描述模式 |
| v1.0.0 | 2024-02 | 初始版本，避障模式 |

---

## ✨ 功能特性

### 1️⃣ 语音控制 (v5.0)

| 命令 | 功能 | 示例语音 |
|------|------|---------|
| 初始化 AI | 启动 AI 推理引擎 | "初始化 AI"、"开始 AI" |
| 拍照识别 | 捕获图像并分析 | "拍照"、"识别"、"分析" |
| 开始检测 | 启动连续检测模式 | "开始检测"、"连续检测" |
| 停止检测 | 停止连续检测 | "停止检测"、"结束检测" |
| 切换模式 | 切换 AI 模式 (1/2/3) | "切换模式"、"模式一" |
| 大声一点 | 增加 TTS 音量 | "大声一点"、"音量调大" |
| 小声一点 | 降低 TTS 音量 | "小声一点"、"音量调小" |
| 说慢点 | 降低 TTS 语速 | "说慢点"、"慢一点" |
| 说快点 | 提高 TTS 语速 | "说快点"、"快一点" |
| 重复 | 重复上一次语音播报 | "重复"、"再说一遍" |
| 暂停 | 暂停语音监听 | "暂停"、"闭嘴" |
| 恢复 | 恢复语音监听 | "恢复"、"继续" |
| 切换用户 | 切换声纹用户 | "切换用户"、"换用户" |
| 注册声纹 | 注册新声纹 | "注册声纹"、"录入声音" |
| 关闭 | 退出应用 | "关闭"、"退出" |

**模糊意图识别 (NLU)**: 支持近义词匹配，例如：
- "听不清" → 自动调大音量
- "说太快" → 自动降低语速
- "再说一次" → 重复上一次播报

### 2️⃣ 声纹识别

- **本地注册**: 录音 5 秒 × 2 次，提取 192 维声纹特征向量
- **离线识别**: 使用 ONNX Runtime 本地推理，无需联网
- **个性化设置**: 根据声纹自动切换：
  - TTS 语速 (0.5x - 2.0x)
  - TTS 音调 (-10 - +10)
  - 识别语言 (中文/英文)
- **多用户支持**: 支持注册多个用户，自动识别当前说话人

**技术实现**:
- 特征提取: FBANK (40 Mel 滤波器) + MFCC
- 模型: ECAPA-TDNN (转 ONNX, 476KB)
- 相似度: 余弦相似度 > 0.45 判定为同一人

### 3️⃣ 三种 AI 模式

| 模式 | 功能 | AI Prompt | 语音输出 |
|------|------|-----------|---------|
| **模式1: 避障** | 识别前方障碍物并估算距离 | "识别图像中的障碍物..." | "前方2米有台阶" |
| **模式2: 文字阅读** | OCR 提取图片中的文字 | "识别图像中的文字..." | "识别到: 出口 →" |
| **模式3: 场景描述** | 描述当前场景 | "描述图像中的场景..." | "你在一个明亮的室内" |

### 4️⃣ 技术栈

| 模块 | 技术 | 说明 |
|------|------|------|
| **云端 AI** | StepFun API (阶跃星辰) | `step-1-flash` (文本) / `step-1v-8k` (视觉) |
| **声纹识别** | ONNX Runtime | ECAPA-TDNN 模型 (476KB) |
| **特征提取** | FBANK + MFCC | 本地 FFT 实现 |
| **摄像头** | CameraX | AndroidX Camera 库 |
| **语音** | Android TTS | 系统自带 TTS 引擎，支持语速/音调/音量控制 |
| **语音识别** | Android SpeechRecognizer | 系统语音识别 (可扩展为离线) |
| **眼镜** | CXR-L SDK (Rokid) | 智能眼镜连接 |
| **架构** | MVC + Controller | MainActivity + 4 个 Controller |

---

## 🏗️ 技术架构

```
┌─────────────────────────────────────────────────────┐
│                   VisionLink Android                  │
├─────────────────────────────────────────────────────┤
│                                                      │
│  ┌────────────┐    ┌────────────┐    ┌────────────┐ │
│  │  UI Layer  │    │  AI Layer   │    │ Audio Layer│ │
│  │             │    │             │    │             ││
│  │ MainActivity│◄──►│ AIController│◄──►│ TTSManager ││
│  │   (HUD)    │    │ (StepFun)  │    │  (Android)  ││
│  └────────────┘    └────────────┘    └────────────┘ │
│         ▲                  ▲                  ▲       │
│         │                  │                  │       │
│  ┌──────┴──────┐   ┌──────┴──────┐   ┌──────┴──────┐│
│  │ Voice Layer │   │VoicePrint  │   │Glasses Layer││
│  │             │   │  Layer      │   │              ││
│  │VoiceCommand │   │VoicePrint  │   │CXRGlasses   ││
│  │Manager (NLU)│   │Manager (ONNX)│   │Manager (Rokid)│
│  └─────────────┘   └─────────────┘   └─────────────┘│
│                                                      │
└─────────────────────────────────────────────────────┘
```

### 控制器架构 (v5.0)

```
MainActivity.kt (1012 行 → 拆分后 ~300 行)
├── VoicePrintController.kt (声纹管理)
├── ContinuousDetectionController.kt (连续检测)
├── GuideModeController.kt (导盲模式)
└── AIController.kt (AI 推理)
```

---

## 🚀 快速开始

### 1️⃣ 克隆项目

```bash
git clone https://github.com/cpufreestyle/visionlink-android.git
cd visionlink-android
```

### 2️⃣ 配置 API Key

编辑 `local.properties` (不要提交到 git):

```properties
stepfun.api.key=你的StepFun API Key
stepfun.api.key.test=你的StepFun API Key
```

**获取 StepFun API Key**:
1. 访问 https://platform.stepfun.com
2. 注册/登录
3. 创建 API Key
4. 复制 Key 并填入 `local.properties`

### 3️⃣ 编译项目

```bash
# Linux/Mac
./gradlew assembleDebug

# Windows
.\gradlew.bat assembleDebug
```

### 4️⃣ 安装到设备

```bash
# 连接 Android 设备 (USB 调试已开启)
./gradlew installDebug
```

---

## ⚙️ 配置说明

### API Key 配置

**方法 A: 使用 `local.properties` (推荐)**

编辑 `local.properties`:

```properties
stepfun.api.key=your_actual_stepfun_api_key
stepfun.api.key.test=your_actual_stepfun_api_key
```

**方法 B: 使用环境变量**

```bash
export STEPFUN_API_KEY="your_actual_stepfun_api_key"
./gradlew assembleDebug
```

### 声纹识别配置

声纹数据存储在 `SharedPreferences`:

```kotlin
// 声纹阈值 (默认 0.45)
VoicePrintManager.setVerifyThreshold(0.45f)
VoicePrintManager.setIdentifyThreshold(0.45f)

// 声纹数据存储在:
// /data/data/com.visionlink.android/shared_prefs/voice_print_prefs.xml
```

### TTS 配置

```kotlin
// TTS 语速 (0.5 - 2.0)
TTSManager.setSpeed(1.0f)

// TTS 音调 (-10 - +10)
TTSManager.setPitch(1.0f)

// TTS 音量 (0.0 - 1.0)
TTSManager.setVolume(1.0f)
```

---

## 🎤 语音命令

### 支持的命令

| 命令 | 关键词 | 功能 |
|------|--------|------|
| 初始化 AI | "初始化"、"开始 AI" | 启动 StepFun API 连接 |
| 拍照识别 | "拍照"、"识别"、"分析" | 捕获图像并发送到 StepFun |
| 开始检测 | "开始检测"、"连续" | 启动连续检测模式 |
| 停止检测 | "停止检测"、"结束" | 停止连续检测 |
| 切换模式 | "切换模式"、"模式一" | 循环切换模式 1/2/3 |
| 大声一点 | "大声"、"音量+" | TTS 音量 +0.1 |
| 小声一点 | "小声"、"音量-" | TTS 音量 -0.1 |
| 说慢点 | "慢点"、"语速-" | TTS 语速 -0.1 |
| 说快点 | "快点"、"语速+" | TTS 语速 +0.1 |
| 重复 | "重复"、"再说" | 重复上一次播报 |
| 暂停 | "暂停"、"闭嘴" | 暂停语音监听 |
| 恢复 | "恢复"、"继续" | 恢复语音监听 |
| 切换用户 | "切换用户"、"换人" | 切换到下一个声纹用户 |
| 注册声纹 | "注册声纹"、"录入" | 启动声纹注册流程 |
| 关闭 | "关闭"、"退出" | 退出应用 |

### 模糊意图识别 (NLU)

系统支持近义词匹配，例如：

- "听不清" → 调大音量
- "说太快" → 降低语速
- "再说一次" → 重复播报
- "看不见" → 启动连续检测

---

## 👤 声纹识别

### 注册声纹

1. 点击主界面底部 **"声纹"** 按钮
2. 输入用户名
3. 点击 **"注册声纹"**
4. 录音 5 秒 (保持安静，靠近麦克风)
5. 重复录音 5 秒 (共 2 次)
6. 注册成功

### 识别声纹

- **自动识别**: 每次语音命令后自动识别
- **手动识别**: 点击 **"识别声纹"** 按钮
- **识别结果**: 如果相似度 > 0.45，自动切换个性化设置

### 管理声纹

- **查看用户列表**: 点击 **"声纹"** 按钮
- **删除用户**: 长按用户名 → 确认删除
- **切换用户**: 说 "切换用户" 或手动选择

### 技术细节

**特征提取**:
- 采样率: 16000 Hz
- 窗口大小: 25 ms
-  hop 长度: 10 ms
-  Mel 滤波器: 40
-  MFCC 系数: 13

**模型**:
- 架构: ECAPA-TDNN
- 输入: 40-dim FBANK
- 输出: 192-dim 向量
- 模型大小: 476 KB (ONNX)
- 推理时间: < 100 ms (骁龙 8 Gen 1)

---

## 🛠️ 开发文档

### 项目结构

```
visionlink-android/
├── app/
│   ├── build.gradle.kts          # App 级构建配置
│   ├── proguard-rules.pro        # 混淆规则
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/
│       │   │   └── ecapa_encoder.onnx  # 声纹模型
│       │   ├── java/com/visionlink/android/
│       │   │   ├── ui/
│       │   │   │   └── MainActivity.kt    # 主界面
│       │   │   ├── ai/
│       │   │   │   ├── AIInferenceManager.kt  # StepFun API
│       │   │   │   └── controller/
│       │   │   │       └── AIController.kt    # AI 控制器
│       │   │   ├── audio/
│       │   │   │   ├── TTSManager.kt         # TTS 管理
│       │   │   │   └── VoiceCommandManager.kt # 语音命令
│       │   │   ├── voiceprint/
│       │   │   │   ├── VoicePrintManager.kt   # 声纹管理
│       │   │   │   ├── VoicePrintDialog.kt    # 声纹 UI
│       │   │   │   └── UserPreferencesDialog.kt # 用户偏好
│       │   │   ├── controller/
│       │   │   │   ├── VoicePrintController.kt
│       │   │   │   ├── ContinuousDetectionController.kt
│       │   │   │   └── GuideModeController.kt
│       │   │   ├── camera/
│       │   │   │   └── CameraManager.kt      # 摄像头
│       │   │   ├── glasses/
│       │   │   │   └── CXRGlassesManager.kt  # Rokid 眼镜
│       │   │   ├── bluetooth/
│       │   │   │   └── BleRingManager.kt     # 蓝牙戒指
│       │   │   └── ui/
│       │   │       └── MainViewModel.kt       # ViewModel
│       │   └── res/layout/
│       │       └── activity_main.xml          # 主界面布局
│       └── test/
│           └── java/com/visionlink/android/
│               ├── audio/
│               │   └── VoiceCommandManagerTest.kt
│               └── voiceprint/
│                   └── VoicePrintManagerTest.kt
├── build.gradle.kts             # 项目级构建配置
├── settings.gradle.kts          # 项目设置
├── local.properties             # API Key 配置 (不提交)
├── RELEASE_v5.1.0.md           # Release Notes
└── README.md                    # 本文档
```

### 核心类说明

#### 1. `AIInferenceManager.kt`

**功能**: StepFun API 推理  
**对应 PC 版**: `ollama.chat()`

```kotlin
// 初始化 (ping StepFun API)
suspend fun initialize(): Boolean {
    val response = pingStepFunAPI()
    return response.isSuccessful
}

// 分析图像 (发送到 StepFun)
suspend fun analyzeImage(bitmap: Bitmap, mode: Int): String {
    val base64Image = bitmapToBase64(bitmap)
    val prompt = buildPrompt(mode)
    return callStepFunAPI(prompt, base64Image)
}
```

#### 2. `VoicePrintManager.kt`

**功能**: 声纹注册和识别  
**技术**: ONNX Runtime + FBANK

```kotlin
// 注册声纹
suspend fun enroll(name: String, audioData: FloatArray): Boolean {
    val embedding = extractEmbedding(audioData)
    saveEmbedding(name, embedding)
    return true
}

// 识别声纹
suspend fun identify(audioData: FloatArray): String? {
    val embedding = extractEmbedding(audioData)
    return findBestMatch(embedding)
}
```

#### 3. `VoiceCommandManager.kt`

**功能**: 语音命令识别 (NLU)  
**支持**: 模糊意图匹配

```kotlin
// 处理语音命令
fun processCommand(text: String): CommandResult {
    val intent = matchIntent(text)  // NLU 模糊匹配
    return executeCommand(intent)
}
```

---

## 🔧 故障排除

### 问题 1: StepFun API 初始化失败

**症状**: 点击 "初始化 AI" 提示失败

**原因**:
1. API Key 未配置或错误
2. 网络未连接
3. StepFun API 服务异常

**解决**:
1. 检查 `local.properties` 中的 `stepfun.api.key`
2. 确认网络可用 (ping api.stepfun.com)
3. 查看 Logcat 错误信息

### 问题 2: 声纹识别不准确

**症状**: 识别率 < 50%

**原因**:
1. 注册时环境嘈杂
2. 录音质量差
3. 阈值设置过高

**解决**:
1. 在安静环境重新注册
2. 降低阈值 (`setVerifyThreshold(0.35f)`)
3. 增加注册录音次数 (当前 2 次，可改为 3 次)

### 问题 3: TTS 无语音输出

**症状**: 识别完成但无语音

**原因**:
1. TTS 引擎未安装
2. 中文语音包缺失
3. 音量设置为 0

**解决**:
1. 安装 Google TTS 引擎
2. 下载中文语音包
3. 说 "大声一点" 或检查音量设置

### 问题 4: 语音命令无响应

**症状**: 说命令但无反应

**原因**:
1. 未授予麦克风权限
2. SpeechRecognizer 未初始化
3. 环境嘈杂

**解决**:
1. 检查权限设置
2. 重启应用
3. 靠近麦克风说话

---

## 🤝 贡献指南

### 贡献流程

1. Fork 项目
2. 创建分支 (`git checkout -b feature/xxx`)
3. 提交更改 (`git commit -m 'Add xxx'`)
4. 推送到分支 (`git push origin feature/xxx`)
5. 创建 Pull Request

### 代码规范

- **Kotlin**: 遵循 [Kotlin 编码规范](https://kotlinlang.org/docs/coding-conventions.html)
- **命名**: 使用驼峰命名法 (`camelCase`)
- **注释**: 所有 public 函数必须有 KDoc 注释
- **测试**: 新功能必须包含单元测试

### 待完成任务

- [ ] 离线语音识别 (替代 Android SpeechRecognizer)
- [ ] 唤醒词检测 (Wake Word)
- [ ] 更多 AI 模式 (人脸识别、货币识别)
- [ ] 优化声纹识别准确率
- [ ] 支持更多智能眼镜 (华为、米家)
- [ ] 添加导航功能 (GPS + 语音引导)

---

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

---

## 🙏 致谢

- **阶跃星辰 (StepFun)** - 提供 StepFun API
- **Rokid** - 提供 CXR-L 智能眼镜 SDK
- **SpeechBrain** - 提供 ECAPA-TDNN 声纹模型
- **TensorFlow** - 提供 ONNX Runtime

---

## 📧 联系方式

- **Issue Tracker**: [GitHub Issues](https://github.com/cpufreestyle/visionlink-android/issues)
- **Email**: your-email@example.com

---

**⭐ 如果这个项目对你有帮助，请给它一个星标！**

**🚀 v5.1.0 - 切换至 StepFun API，提供更稳定的 AI 服务！**
