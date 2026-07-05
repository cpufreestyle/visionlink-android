# VisionLink Android v5.1.0 Release Notes

## 主要变更

### 🔄 切换 AI 提供商：Moonshot → StepFun (阶跃星辰)

- **AIInferenceManager**: 替换 Moonshot API 为 StepFun API
  - API 端点: `https://api.stepfun.com/v1/chat/completions`
  - Text 模型: `step-1-flash` (快速) 或 `step-1` (标准)
  - Vision 模型: `step-1v-8k` (支持图像识别)
- **AIController**: `ENGINE_STEPFUN` 替代 `ENGINE_MOONSHOT`
- **MainActivity**: 引擎显示名更新为 "StepFun API (阶跃星辰)"

### ⚙️ 配置变更

1. **local.properties** - 需要更新：
   ```properties
   stepfun.api.key=你的StepFun API Key
   stepfun.api.key.test=你的StepFun API Key
   ```

2. **获取 API Key**: 访问 https://platform.stepfun.com

### 🐛 修复

- 修复通配符导入问题（11 个文件）
- 修复 `import import` 双重关键字错误
- 修复 `build.gradle.kts` 中 Kotlin DSL extra 属性语法

## 安装说明

1. 下载 APK 文件
2. 在 Android 设备上启用"未知来源"安装
3. 安装 APK
4. 首次运行需要授予权限（相机、麦克风、蓝牙）

## 配置 StepFun API Key

编译前，在 `local.properties` 中填入你的 StepFun API Key：

```properties
stepfun.api.key=your_actual_stepfun_api_key
stepfun.api.key.test=your_actual_stepfun_api_key
```

## 常见问题

**Q: 为什么切换从 Moonshot 到 StepFun？**
A: StepFun 提供更稳定的 API 服务和更好的中文支持。

**Q: 如何获取 StepFun API Key？**
A: 访问 https://platform.stepfun.com 注册并创建 API Key。

**Q: 编译失败怎么办？**
A: 确保已安装：
- JDK 17+
- Android SDK (API 35)
- Android Studio (推荐)

## 技术细节

- 编译 SDK: 35
- 最低 SDK: 31 (Android 12)
- 目标 SDK: 35 (Android 15)
- Kotlin 版本: 2.3.0
- Gradle 版本: 8.8
- AGP 版本: 8.5.0

## 完整变更日志

查看 Git 提交历史：
- `fe30a65`: 全量优化 — 20项改进 (v5.0.0)
- `827d32b`: 切换 AI API 从 Moonshot 到 StepFun (v5.1.0)

## 下载

- Debug APK: `app-debug.apk` (开发测试用)
- Release APK: `app-release.apk` (正式发布，需签名)

---

**注意**: 这是预发布版本，建议在测试后再用于生产环境。
