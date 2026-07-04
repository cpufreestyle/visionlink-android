# Changelog

All notable changes to VisionLink Android will be documented in this file.

## [5.0.0] - 2026-07-05

### Added
- 端侧声纹识别 (ECAPA-TDNN ONNX, 192维向量, 余弦相似度比对)
- 语音控制系统 v5.0 (音量/语速/重复/暂停/恢复/切换用户/注册声纹)
- 声纹门控 (受保护命令需身份验证)
- 用户个性化偏好 (模式/语言/语速/TTS音调/音量)
- VoicePrintDialog 用户管理 UI
- UserPreferencesDialog 偏好设置 UI
- ONNX Runtime Android 1.18.0 集成
- 说话人分离 API (pyannote.audio)
- Rokid 眼镜音频桥接 (CXR-M SDK)
- TTS 语速/音调/音量独立控制

### Changed
- versionCode 10 → 50, versionName 4.9.1 → 5.0.0
- API Key 从硬编码改为 BuildConfig + local.properties 注入
- ONNX Runtime 从反射调用改为强类型 API
- 全局 cleartext traffic 改为 network-security-config 精确控制
- litertlm-android 从 latest.release 固定为 0.13.1
- TTS volume 参数实际生效 (KEY_PARAM_VOLUME)
- PAUSE 命令同时暂停语音识别
- RESUME 命令恢复语音识别
- 声纹门控使用 currentUserId 而非第一个注册用户
- AI initialize() 增加 Moonshot API ping 检查

### Fixed
- API Key 泄露 (硬编码 → BuildConfig 注入)
- app-debug.zip 误提交到 Git
- TTS volume 变量无实际效果
- 声纹门控验证错误用户
- PAUSE/RESUME 命令逻辑不完整
- AI initialize() 不检查 API Key 有效性

### Removed
- app-debug.zip 从 Git 跟踪中移除

## [4.9.1] - 2026-06-28

### Added
- Gemma 4 E2B 端侧 AI 推理
- 多引擎支持 (Moonshot / LM Studio / LiteRT-LM)
- 避障/读文本/场景描述/指向引导 四种模式
- 蓝牙指环控制
- CXR 眼镜画面投射
- 连续检测模式
- 目标锁定/解锁

## [1.0.0] - 2026-06-10

### Added
- 项目初始化
- 基础相机 + AI 分析功能
- TTS 语音播报
- 语音命令识别
