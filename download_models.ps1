# VisionLink Android - 模型下载脚本
# 自动下载 Gemma 4 E2B (.litertlm) 和视觉模型 (.tflite)

Write-Host "📥 VisionLink Android - 模型下载工具" -ForegroundColor Cyan
Write-Host "======================================" -ForegroundColor Cyan

# 配置
$ProjectDir = "D:\qclaw-workspace\visionlink-android"
$ModelsDir = "$ProjectDir\app\src\main\assets\models"
$GemmaModelUrl = "https://storage.googleapis.com/litert-lm/gemma-4-e2b-it.litertlm"
$VisionModelUrl = "https://tfhub.dev/tensorflow/lite/vision/classification/mobilenet_v3_1.0_224/1"

# 创建模型目录
if (!(Test-Path $ModelsDir)) {
    New-Item -ItemType Directory -Path $ModelsDir -Force | Out-Null
    Write-Host "✅ 创建模型目录: $ModelsDir" -ForegroundColor Green
}

# ============ 下载 Gemma 4 E2B 模型 ============
Write-Host ""
Write-Host "📦 [1/2] 下载 Gemma 4 E2B 模型..." -ForegroundColor Yellow

$GemmaModelPath = "$ModelsDir\gemma-4-e2b-it.litertlm"

if (Test-Path $GemmaModelPath) {
    Write-Host "⚠️  Gemma 模型已存在，跳过下载" -ForegroundColor Yellow
} else {
    Write-Host "🔗 下载地址: <ADDRESS_REMOVED>
    Write-Host "⚠️  注意: 需要从 developers.google.com/litert-lm 获取真实下载链接" -ForegroundColor Red
    
    # 尝试下载 (如果链接有效)
    try {
        Write-Host "⏳ 正在下载 Gemma 4 E2B 模型 (约 4GB)..." -ForegroundColor Cyan
        Invoke-WebRequest -Uri $GemmaModelUrl -OutFile $GemmaModelPath -UseBasicParsing
        
        if (Test-Path $GemmaModelPath) {
            $FileSize = (Get-Item $GemmaModelPath).Length / 1MB
            Write-Host "✅ Gemma 模型下载成功 ($($FileSize.ToString('0.00')) MB)" -ForegroundColor Green
        }
    } catch {
        Write-Host "❌ 下载失败: $_" -ForegroundColor Red
        Write-Host "📝 请手动下载并放入: $GemmaModelPath" -ForegroundColor Yellow
        Write-Host "   1. 访问: https://developers.google.com/litert-lm/docs/get-started" -ForegroundColor Yellow
        Write-Host "   2. 下载 gemma-4-e2b-it.litertlm" -ForegroundColor Yellow
        Write-Host "   3. 放入: $ModelsDir\" -ForegroundColor Yellow
    }
}

# ============ 下载视觉模型 (.tflite) ============
Write-Host ""
Write-Host "📦 [2/2] 下载视觉模型 (MobileNetV3)..." -ForegroundColor Yellow

$VisionModelPath = "$ModelsDir\mobilenet_v3.tflite"

if (Test-Path $VisionModelPath) {
    Write-Host "⚠️  视觉模型已存在，跳过下载" -ForegroundColor Yellow
} else {
    Write-Host "🔗 下载地址: <ADDRESS_REMOVED>
    Write-Host "⚠️  注意: 需要从 TensorFlow Hub 获取真实下载链接" -ForegroundColor Red
    
    # 尝试下载
    try {
        Write-Host "⏳ 正在下载 MobileNetV3 模型..." -ForegroundColor Cyan
        Invoke-WebRequest -Uri $VisionModelUrl -OutFile $VisionModelPath -UseBasicParsing
        
        if (Test-Path $VisionModelPath) {
            $FileSize = (Get-Item $VisionModelPath).Length / 1MB
            Write-Host "✅ 视觉模型下载成功 ($($FileSize.ToString('0.00')) MB)" -ForegroundColor Green
        }
    } catch {
        Write-Host "❌ 下载失败: $_" -ForegroundColor Red
        Write-Host "📝 请手动下载并放入: $VisionModelPath" -ForegroundColor Yellow
        Write-Host "   1. 访问: https://www.tensorflow.org/lite/models" -ForegroundColor Yellow
        Write-Host "   2. 下载 MobileNetV3 .tflite 模型" -ForegroundColor Yellow
        Write-Host "   3. 放入: $ModelsDir\" -ForegroundColor Yellow
    }
}

# ============ 验证模型文件 ============
Write-Host ""
Write-Host "🔍 验证模型文件..." -ForegroundColor Cyan

$Files = @(
    @{Name="Gemma 4 E2B"; Path=$GemmaModelPath; MinSizeMB=1000},
    @{Name="MobileNetV3"; Path=$VisionModelPath; MinSizeMB=10}
)

foreach ($File in $Files) {
    if (Test-Path $File.Path) {
        $SizeMB = (Get-Item $File.Path).Length / 1MB
        if ($SizeMB -ge $File.MinSizeMB) {
            Write-Host "✅ $($File.Name): $($SizeMB.ToString('0.00')) MB" -ForegroundColor Green
        } else {
            Write-Host "⚠️  $($File.Name): 文件过小 ($($SizeMB.ToString('0.00')) MB)，可能下载不完整" -ForegroundColor Yellow
        }
    } else {
        Write-Host "❌ $($File.Name): 文件不存在" -ForegroundColor Red
    }
}

# ============ 生成模型配置 ============
Write-Host ""
Write-Host "📝 生成模型配置文件..." -ForegroundColor Cyan

$ConfigPath = "$ModelsDir\model_config.json"

$Config = @{
    gemma = @{
        model_name = "gemma-4-e2b-it"
        model_format = "litertlm"
        model_path = "models/gemma-4-e2b-it.litertlm"
        temperature = 0.1
        max_tokens = 256
    }
    vision = @{
        model_name = "mobilenet_v3"
        model_format = "tflite"
        model_path = "models/mobilenet_v3.tflite"
        input_size = 224
        num_channels = 3
    }
} | ConvertTo-Json -Depth 10

$Config | Out-File -FilePath $ConfigPath -Encoding UTF8
Write-Host "✅ 模型配置已生成: $ConfigPath" -ForegroundColor Green

# ============ 完成 ============
Write-Host ""
Write-Host "======================================" -ForegroundColor Cyan
Write-Host "✅ 模型下载完成！" -ForegroundColor Green
Write-Host ""
Write-Host "📋 下一步:" -ForegroundColor Yellow
Write-Host "   1. 如果模型下载失败，请手动下载并放入:" -ForegroundColor Yellow
Write-Host "      - Gemma 4 E2B: $ModelsDir\" -ForegroundColor Yellow
Write-Host "      - 视觉模型: $ModelsDir\" -ForegroundColor Yellow
Write-Host "   2. 编译项目: cd $ProjectDir && .\gradlew assembleDebug" -ForegroundColor Yellow
Write-Host "   3. 安装到设备: .\gradlew installDebug" -ForegroundColor Yellow
Write-Host ""
