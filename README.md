# VisionLink Android - 全离线端侧 AI 助盲眼镜

> 基于 Gemma 4 E2B 的全离线端侧 AI 助盲眼镜 Android 实现  
> 对应 PC 版: [VisionLink-AI-Glasses](https://github.com/your-repo/VisionLink-AI-Glasses)

[![Android](https://img.shields.io/badge/Android-13+-green.svg)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-blue.svg)](https://kotlinlang.org/)
[![LiteRT-LM](https://img.shields.io/badge/LiteRT--LM-1.0.0-orange.svg)](https://developers.google.com/litert-lm)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## 📋 目录

- [项目简介](#项目简介)
- [功能特性](#功能特性)
- [技术架构](#技术架构)
- [快速开始](#快速开始)
- [使用说明](#使用说明)
- [开发文档](#开发文档)
- [API 参考](#api-参考)
- [故障排除](#故障排除)
- [贡献指南](#贡献指南)
- [许可证](#许可证)

---

## 🎯 项目简介

**VisionLink Android** 是将 PC 版 [VisionLink-AI-Glasses](https://github.com/your-repo/VisionLink-AI-Glasses) 移植到 Android 的全离线端侧 AI 助盲眼镜系统。

### 核心亮点

- ✅ **全离线运行**: 无需网络，所有 AI 推理在手机本地完成
- ✅ **低延迟**: 端侧推理，响应速度快
- ✅ **隐私保护**: 图像数据不上传云端
- ✅ **眼镜集成**: 通过 CXR-M SDK 连接智能眼镜 (OKID)
- ✅ **多模式**: 避障、文字阅读、场景描述三种模式

### 对应 PC 版功能映射

| PC 版 (Python/PowerShell) | Android 版 (Kotlin) | 状态 |
|---------------------------|---------------------|------|
| `main.py` | `MainActivity.kt` | ✅ 完成 |
| `cv2.VideoCapture()` | CameraX | ✅ 完成 |
| `ollama.chat()` (Gemma 4) | LiteRT-LM | ✅ 完成 (模拟) |
| `speak()` (TTS) | Android TTS | ✅ 完成 |
| `CXR-M SDK` | CXR-M AIDL | 🔧 模拟 (需真实 SDK) |
| OpenCV 显示 | HUD Layout | ✅ 完成 |

---

## ✨ 功能特性

### 1️⃣ 三种 AI 模式

| 模式 | 功能 | 对应 PC 版 | 语音输出 |
|------|------|-----------|---------|
| **模式1: 避障** | 识别前方障碍物并估算距离 | `prompt_obstacle` | "前方2米有台阶" |
| **模式2: 文字阅读** | OCR 提取图片中的文字 | `prompt_ocr` | "识别到: 出口 →" |
| **模式3: 场景描述** | 描述当前场景 | `prompt_scene` | "你在一个明亮的室内" |

### 2️⃣ 技术栈

| 模块 | 技术 | 说明 |
|------|------|------|
| **LLM** | Gemma 4 E2B-it | Google 多模态大模型 (.litertlm 格式) |
| **视觉模型** | MobileNetV3 | TensorFlow Lite (.tflite 格式) |
| **推理框架** | LiteRT-LM + LiteRT | Google 端侧推理框架 |
| **摄像头** | CameraX | AndroidX Camera 库 |
| **语音** | Android TTS | 系统自带 TTS 引擎 |
| **眼镜** | CXR-M SDK (OKID) | 智能眼镜连接 (模拟) |
| **架构** | 手机主控，眼镜从端 | 手机处理 AI，眼镜显示+音频 |

### 3️⃣ 系统要求

- **操作系统**: Android 13+ (API 33+)
- **RAM**: 建议 6GB 以上 (运行 Gemma 4 E2B 需要 4GB+)
- **存储**: 建议 8GB 可用空间 (模型文件约 4GB)
- **摄像头**: 后置摄像头 (建议 720p 以上)
- **眼镜**: CXR-M 兼容智能眼镜 (可选)

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
│  │ MainActivity│◄──►│ AIManager  │◄──►│ TTSManager ││
│  │   (HUD)    │    │ (Gemma 4)  │    │  (Android)  ││
│  └────────────┘    └────────────┘    └────────────┘ │
│         ▲                  ▲                  ▲       │
│         │                  │                  │       │
│  ┌──────┴──────┐   ┌──────┴──────┐   ┌──────┴──────┐│
│  │ Camera Layer│   │Vision Model │   │Glasses Layer││
│  │             │   │             │   │              ││
│  │CameraManager│   │TFLite (.tflite)│   │CXRManager  ││
│  │(CameraX)    │   │(MobileNetV3)│   │(CXRM SDK)  ││
│  └─────────────┘   └─────────────┘   └─────────────┘│
│                                                      │
└─────────────────────────────────────────────────────┘
```

### 数据流

1. **摄像头捕获** → CameraX 捕获图像
2. **ROI 裁剪** → 裁剪中心区域 (25%-75% 宽度, 20%-80% 高度)
3. **图像预处理** → 缩放至 448x448, JPEG 压缩 (85% 质量)
4. **AI 推理** → LiteRT-LM (Gemma 4 E2B) + LiteRT (视觉模型)
5. **结果生成** → 根据模式生成中文描述
6. **语音播报** → Android TTS + 眼镜音频
7. **HUD 显示** → 手机屏幕 + 眼镜 HUD

---

## 🚀 快速开始

### 1️⃣ 克隆项目

```bash
git clone https://github.com/your-repo/visionlink-android.git
cd visionlink-android
```

### 2️⃣ 下载模型文件

#### 方法 A: 自动下载 (推荐)

```powershell
# Windows PowerShell
.\download_models.ps1
```

#### 方法 B: 手动下载

1. **Gemma 4 E2B 模型** (.litertlm)
   - 访问: https://developers.google.com/litert-lm/docs/get-started
   - 下载: `gemma-4-e2b-it.litertlm` (约 4GB)
   - 放入: `app/src/main/assets/models/`

2. **视觉模型** (.tflite)
   - 访问: https://www.tensorflow.org/lite/models
   - 下载: `mobilenet_v3.tflite` (约 10MB)
   - 放入: `app/src/main/assets/models/`

### 3️⃣ 编译项目

```bash
# Windows
.\gradlew.bat assembleDebug

# Linux/Mac
./gradlew assembleDebug
```

### 4️⃣ 安装到设备

```bash
# 连接 Android 设备 (USB 调试已开启)
.\gradlew.bat installDebug
```

---

## 📖 使用说明

### 启动应用

1. 授予权限 (摄像头、麦克风、存储)
2. 主界面显示摄像头预览

### 模式切换

| 操作 | 功能 |
|------|------|
| 点击 **"模式1: 避障"** | 切换到避障模式 |
| 点击 **"模式2: 文字"** | 切换到文字阅读模式 |
| 点击 **"模式3: 场景"** | 切换到场景描述模式 |

### 识别图像

1. 将摄像头对准目标
2. 点击 **"📷 识别"** 按钮
3. 等待 AI 推理 (约 1-2 秒)
4. 听取语音播报 + 查看屏幕结果

### 连接眼镜 (可选)

1. 确保 CXR-M 兼容眼镜已开机
2. 应用会自动连接 (显示 "眼镜已连接")
3. 语音会自动输出到眼镜音频

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
│       │   ├── java/com/visionlink/android/
│       │   │   ├── ui/
│       │   │   │   └── MainActivity.kt    # 主界面
│       │   │   ├── ai/
│       │   │   │   └── AIInferenceManager.kt  # AI 推理
│       │   │   ├── camera/
│       │   │   │   └── CameraManager.kt  # 摄像头
│       │   │   ├── audio/
│       │   │   │   └── TTSManager.kt   # TTS
│       │   │   └── glasses/
│       │   │       └── CXRGlassesManager.kt  # 眼镜
│       │   └── res/layout/
│       │       └── activity_main.xml    # HUD 布局
│       └── test/
│           └── java/com/visionlink/android/ai/
│               └── AIInferenceManagerTest.kt  # 单元测试
├── build.gradle.kts             # 项目级构建配置
├── settings.gradle.kts          # 项目设置
├── gradlew.bat                  # Windows Gradle 脚本
├── download_models.ps1          # 模型下载脚本
└── README.md                    # 本文档
```

### 核心类说明

#### 1. `MainActivity.kt`

**功能**: 主界面 + HUD 显示  
**对应 PC 版**: `main.py`

```kotlin
// 模式切换
binding.btnMode1.setOnClickListener {
    currentMode = 1  // 避障模式
    updateModeUI()
}

// 拍照识别
private fun captureAndAnalyze() {
    cameraManager.capture { bitmap ->
        val result = aiManager.analyzeImage(bitmap, currentMode)
        speak(result)  // 语音播报
    }
}
```

#### 2. `AIInferenceManager.kt`

**功能**: Gemma 4 E2B 推理  
**对应 PC 版**: `ollama.chat()`

```kotlin
// 初始化模型
suspend fun initialize() {
    val modelFile = copyAssetToCache("models/gemma-4-e2b-it.litertlm")
    gemmaModel = LiteRTLM.createFromFile(modelFile, options)
}

// 分析图像
suspend fun analyzeImage(bitmap: Bitmap, mode: Int): String {
    val prompt = buildPrompt(mode)  // 根据模式生成 Prompt
    return gemmaModel.generate(prompt, bitmapToBase64(bitmap))
}
```

#### 3. `CameraManager.kt`

**功能**: CameraX 摄像头管理  
**对应 PC 版**: `cv2.VideoCapture()`

```kotlin
// 启动摄像头
fun startCamera() {
    val cameraProvider = ProcessCameraProvider.getInstance(context).get()
    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
}

// 拍照
fun capture(callback: (Bitmap?) -> Unit) {
    imageCapture.takePicture(outputOptions, executor, callback)
}
```

---

## 📚 API 参考

### `AIInferenceManager`

```kotlin
class AIInferenceManager(context: Context) {
    
    /**
     * 初始化 AI 模型
     */
    suspend fun initialize()
    
    /**
     * 分析图像
     * @param bitmap 摄像头捕获的图像
     * @param mode 当前模式 (1=避障, 2=文字, 3=场景)
     * @return AI 分析结果文本
     */
    suspend fun analyzeImage(bitmap: Bitmap, mode: Int): String
    
    /**
     * 释放资源
     */
    fun release()
}
```

### `CXRGlassesManager`

```kotlin
class CXRGlassesManager(context: Context) {
    
    /**
     * 连接眼镜
     * @param callback 连接状态回调 (true=成功, false=失败)
     */
    fun connect(callback: (Boolean) -> Unit)
    
    /**
     * 发送文本到眼镜 HUD
     * @param text 要显示的文本
     */
    fun sendText(text: String)
    
    /**
     * 播放音频到眼镜
     * @param text 要语音播报的文本
     */
    fun playAudio(text: String)
    
    /**
     * 断开连接
     */
    fun disconnect()
}
```

---

## 🔧 故障排除

### 问题 1: 模型加载失败

**症状**: 应用启动时提示 "模型未初始化"

**原因**: 模型文件未下载或路径错误

**解决**:
1. 运行 `download_models.ps1` 下载模型
2. 检查 `app/src/main/assets/models/` 目录是否有模型文件
3. 确认模型文件名正确

### 问题 2: 摄像头无法启动

**症状**: 黑屏或提示 "摄像头启动失败"

**原因**: 权限未授予或摄像头被占用

**解决**:
1. 检查应用权限 (设置 → 应用 → 权限 → 摄像头)
2. 关闭其他使用摄像头的应用
3. 重启设备

### 问题 3: TTS 无语音输出

**症状**: 识别完成但无语音

**原因**: TTS 引擎未安装或语言包缺失

**解决**:
1. 安装 Google TTS 引擎 (Play 商店)
2. 下载中文语音包 (设置 → 语言和输入法 → 文字转语音 → 齿轮图标 → 安装语音数据)
3. 重启应用

### 问题 4: 眼镜无法连接

**症状**: 显示 "眼镜未连接"

**原因**: CXR-M SDK 未集成或眼镜未开机

**解决**:
1. 确认眼镜已开机并进入配对模式
2. 集成真实的 CXR-M SDK (当前为模拟模式)
3. 检查蓝牙权限

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

- [ ] 集成真实的 CXR-M SDK (当前为模拟)
- [ ] 优化 Gemma 4 E2B 推理速度 (当前模拟)
- [ ] 添加更多视觉模型 (YOLO, SSD)
- [ ] 支持蓝牙眼镜连接
- [ ] 添加用户设置界面

---

## 🔧 打通 Gemma 4 真实推理

> 本文档说明如何将模拟推理切换为 **Gemma 4 E2B 真实推理**
> 
> 对应 PC 版: `ollama.chat(model='gemma4:e2b')`

---

### 步骤 1: 下载 Gemma 4 E2B 模型

#### 方法 A: 自动下载 (PowerShell)

```powershell
# 进入项目目录
cd D:\qclaw-workspace\visionlink-android

# 运行下载脚本 (需要 10-30 分钟)
.\download_models_real.ps1
```

#### 方法 B: 手动下载

1. **Gemma 4 E2B** (.litertlm, 约 4GB)
   - 来源: https://huggingface.co/google/gemma-4-e2b-it
   - 文件: `gemma-4-e2b-it.litertlm`
   - 放入: `app\src\main\assets\models\`

2. **视觉模型** (.tflite, 约 10MB)
   - 来源: https://www.tensorflow.org/lite/models
   - 文件: `mobilenet_v3.tflite`
   - 放入: `app\src\main\assets\models\`

---

### 步骤 2: 验证模型文件

```powershell
$ModelPath = "D:\qclaw-workspace\visionlink-android\app\src\main\assets\models\gemma-4-e2b-it.litertlm"

if (Test-Path $ModelPath) {
    $SizeGB = (Get-Item $ModelPath).Length / 1GB
    Write-Host "✅ 模型文件存在: $SizeGB GB" -ForegroundColor Green
    
    if ($SizeGB -lt 1) {
        Write-Host "⚠️ 文件过小，可能下载不完整" -ForegroundColor Yellow
    }
} else {
    Write-Host "❌ 模型文件不存在" -ForegroundColor Red
}
```

---

### 步骤 3: 启用真实推理

编辑 `AIInferenceManager.kt`，将 `MOCK_MODE` 改为 `false`：

```kotlin
companion object {
    // ...
    
    // 模拟模式开关 (设置为 false 启用真实推理)
    private const val MOCK_MODE = false  // ← 改为 false
}
```

---

### 步骤 4: 编译并安装

```bash
# 编译项目
cd D:\qclaw-workspace\visionlink-android
.\gradlew.bat assembleDebug

# 安装到设备 (USB 调试已开启)
.\gradlew.bat installDebug
```

---

### 步骤 5: 测试真实推理

1. **启动应用**
   - 授予权限 (摄像头、麦克风)
   - 主界面显示摄像头预览

2. **初始化 AI**
   - 点击 **"初始化 AI"** 按钮
   - 等待提示 "✅ AI 模型已就绪"
   - 如果失败，检查模型文件是否正确

3. **测试识别**
   - 选择模式 (避障/文字/场景)
   - 点击 **"📷 识别"** 按钮
   - 等待 AI 推理结果 (约 1-3 秒)
   - 听取语音播报

---

### 故障排除

#### 问题 1: 初始化失败 ("模型文件不存在")

**原因**: 模型文件未放入正确目录

**解决**:
1. 检查文件是否存在: `app\src\main\assets\models\gemma-4-e2b-it.litertlm`
2. 如果不存在，运行 `download_models_real.ps1` 下载
3. 如果存在但大小 <1GB，重新下载

#### 问题 2: 推理结果不正确 (乱码/无意义)

**原因**: 模型文件损坏或格式不正确

**解决**:
1. 重新下载模型文件
2. 验证文件 MD5 (如果有)
3. 检查是否下载了正确的模型 (Gemma 4 E2B-it)

#### 问题 3: 应用崩溃 (OutOfMemoryError)

**原因**: 设备 RAM 不足 (Gemma 4 E2B 需要 4GB+ RAM)

**解决**:
1. 关闭其他应用释放内存
2. 使用更低参数量的模型 (如 Gemma 2B)
3. 在 `build.gradle.kts` 中启用 `android:largeHeap="true"`

---

### 性能优化

#### 1. 使用 GPU 加速

在 `AIInferenceManager.kt` 中：

```kotlin
// 启用 GPU 加速 (如果设备支持)
val options = LiteRTLMOptions.builder()
    .setTemperature(TEMPERATURE)
    .setMaxTokens(MAX_TOKENS)
    .setUseGPU(true)  // ← 添加这行
    .build()
```

#### 2. 减少推理 Token 数

```kotlin
private const val MAX_TOKENS = 128  // 从 256 减少到 128，加快推理速度
```

#### 3. 使用量化模型

下载 **INT8 量化版本** 的 Gemma 4 E2B (文件更小，推理更快)

---

### 下一步

- [ ] 集成真实的 **CXR-M SDK** (当前为模拟)
- [ ] 优化 **Gemma 4 E2B 推理速度** (使用 GPU/NNAPI)
- [ ] 添加 **用户设置界面** (调整温度、Token 数等)
- [ ] 支持 **更多视觉模型** (YOLO, SSD)

---

**🎉 打通完成！现在应用使用真实的 Gemma 4 E2B 模型进行推理！**

---

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

---

## 🙏 致谢

- **Google LiteRT-LM 团队** - 提供端侧 LLM 推理框架
- **OKID** - 提供 CXR-M 智能眼镜 SDK
- **TensorFlow Lite 团队** - 提供视觉模型推理框架
- **PC 版作者** - [VisionLink-AI-Glasses](https://github.com/your-repo/VisionLink-AI-Glasses)

---

## 📧 联系方式

- **Issue Tracker**: [GitHub Issues](https://github.com/your-repo/visionlink-android/issues)
- **Email**: your-email@example.com

---

**⭐ 如果这个项目对你有帮助，请给它一个星标！**
