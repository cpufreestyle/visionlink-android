# VisionLink Android - 模型传输指南

> 如果你用手机下载了 Gemma 4 模型，需要将模型文件传到 PC 才能编译项目

---

## 📋 场景说明

**情况**: 你用手机 (Google AI Edge 或其他方式) 安装了 Gemma 4 模型  
**目标**: 将模型文件传到 PC，放入 `visionlink-android\app\src\main\assets\models\` 目录

---

## 方法 1: USB 数据线传输 (推荐)

### 步骤

1. **连接手机到 PC**
   - 使用 USB 数据线连接
   - 手机上选择 "文件传输" 模式 (MTP)

2. **在手机上找到模型文件**
   - 打开手机 "文件管理" 应用
   - 查找 Gemma 4 模型文件:
     - 文件名: `gemma-4-e2b-it.litertlm`
     - 大小: 约 4GB
   - 常见位置:
     - `Download/` (下载目录)
     - `DCIM/` (相机目录)
     - `Android/data/` (应用数据目录)
     - `Google/AI Edge/` (如果是 Google AI Edge 下载的)

3. **复制到 PC**
   - 在 PC 上打开 "此电脑" → "手机名称"
   - 找到模型文件
   - 复制 (Ctrl+C)
   - 粘贴 (Ctrl+V) 到:
     ```
     D:\qclaw-workspace\visionlink-android\app\src\main\assets\models\
     ```

4. **验证文件**
   - 打开 `D:\qclaw-workspace\visionlink-android\app\src\main\assets\models\`
   - 确认 `gemma-4-e2b-it.litertlm` 存在
   - 右键 → 属性 → 确认大小 > 1GB

---

## 方法 2: 云服务传输 (如果模型文件 < 2GB)

### 2.1 使用 Google Drive

1. **手机上传**
   - 打开 Google Drive 应用
   - 点击 "+" → "上传" → 选择 `gemma-4-e2b-it.litertlm`
   - 等待上传完成

2. **PC 下载**
   - 打开 https://drive.google.com
   - 找到上传的模型文件
   - 下载到:
     ```
     D:\qclaw-workspace\visionlink-android\app\src\main\assets\models\
     ```

### 2.2 使用百度网盘 (国内推荐)

1. **手机上传**
   - 打开百度网盘应用
   - 点击 "+" → "上传文件" → 选择模型文件
   - 等待上传完成

2. **PC 下载**
   - 打开 https://pan.baidu.com
   - 找到上传的模型文件
   - 下载到 PC

⚠️ **注意**: 如果模型文件 > 2GB，免费云服务可能无法上传

---

## 方法 3: ADB 拉取 (高级用户)

### 前提条件

- 手机已开启 **USB 调试**
- PC 已安装 **ADB 工具**

### 步骤

1. **查找模型文件路径**
   ```bash
   # 连接手机
   adb devices
   
   # 在手机上找到模型文件路径
   adb shell
   find /sdcard -name "gemma-4-e2b-it.litertlm"
   exit
   ```

2. **拉取模型文件**
   ```bash
   # 假设路径是 /sdcard/Download/gemma-4-e2b-it.litertlm
   adb pull /sdcard/Download/gemma-4-e2b-it.litertlm D:\qclaw-workspace\visionlink-android\app\src\main\assets\models\
   ```

3. **验证文件**
   - 检查文件是否存在
   - 检查文件大小

---

## 方法 4: 直接在手机上下载模型到 PC (最简单)

如果你有电脑，可以直接在 **PC 上下载模型**，不需要从手机传输！

### 步骤

1. **在 PC 上运行下载脚本**
   ```powershell
   cd D:\qclaw-workspace\visionlink-android
   .\download_models_real.ps1
   ```

2. **等待下载完成** (约 10-30 分钟，取决于网速)

3. **验证模型文件**
   ```powershell
   dir D:\qclaw-workspace\visionlink-android\app\src\main\assets\models\
   ```

---

## 📂 模型文件位置 (最终)

模型文件必须放在:

```
D:\qclaw-workspace\visionlink-android\app\src\main\assets\models\
├── gemma-4-e2b-it.litertlm   (约 4GB)
└── mobilenet_v3.tflite          (约 10MB)
```

---

## ✅ 验证模型文件

### PowerShell 验证脚本

```powershell
$ModelPath = "D:\qclaw-workspace\visionlink-android\app\src\main\assets\models\gemma-4-e2b-it.litertlm"

if (Test-Path $ModelPath) {
    $SizeGB = (Get-Item $ModelPath).Length / 1GB
    Write-Host "✅ 模型文件存在: $SizeGB GB" -ForegroundColor Green
    
    if ($SizeGB -lt 1) {
        Write-Host "⚠️  文件过小，可能下载不完整" -ForegroundColor Yellow
    }
} else {
    Write-Host "❌ 模型文件不存在" -ForegroundColor Red
}
```

---

## 🔧 故障排除

### 问题 1: 找不到模型文件

**原因**: 不知道模型文件下载到哪里了

**解决**:
1. 在手机上搜索 `gemma`
2. 检查常见下载目录 (`Download/`, `DCIM/`)
3. 如果是 Google AI Edge 下载的，检查 `Android/data/com.google.ai.edge/files/`

### 问题 2: 模型文件太大，无法传输

**原因**: 一些传输方式对文件大小有限制

**解决**:
1. 使用 **USB 数据线** (无文件大小限制)
2. 使用 **ADB pull** (高级用户)
3. 直接在 **PC 上下载** (运行 `download_models_real.ps1`)

### 问题 3: 模型文件损坏

**原因**: 传输过程中断或下载不完整

**解决**:
1. 比较文件大小 (手机上 vs PC 上)
2. 如果大小不一致，重新传输
3. 验证文件 MD5 (高级用户)

---

## 📋 快速检查清单

- [ ] 模型文件 `gemma-4-e2b-it.litertlm` 已下载/传输到 PC
- [ ] 文件大小 > 1GB (否则可能不完整)
- [ ] 文件已放入 `app\src\main\assets\models\` 目录
- [ ] 运行 `download_models_real.ps1` 验证通过
- [ ] 编译项目成功 (`.\gradlew assembleDebug`)

---

## 📞 需要帮助？

如果遇到问题，请提供:
1. 手机型号
2. 模型文件位置 (手机上的路径)
3. 传输方法的错误信息 (如果有)

---

**🎉 传输完成后，继续编译项目！**

```bash
cd D:\qclaw-workspace\visionlink-android
.\gradlew.bat assembleDebug
```
