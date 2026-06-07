# VisionLink Android - 真实模型下载脚本
# 从 Hugging Face 和 TensorFlow Hub 下载 Gemma 4 E2B 和视觉模型

Write-Host "📥 VisionLink Android - 真实模型下载工具" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

# 配置
$ProjectDir = "D:\qclaw-workspace\visionlink-android"
$ModelsDir = "$ProjectDir\app\src\main\assets\models"

# 创建模型目录
if (!(Test-Path $ModelsDir)) {
    New-Item -ItemType Directory -Path $ModelsDir -Force | Out-Null
    Write-Host "✅ 创建模型目录: $ModelsDir" -ForegroundColor Green
}

# ============ 下载 Gemma 4 E2B 模型 ============
Write-Host ""
Write-Host "📦 [1/2] 下载 Gemma 4 E2B 模型..." -ForegroundColor Yellow
Write-Host "   来源: Hugging Face (Google 官方)" -ForegroundColor Gray
Write-Host ""

$GemmaModelPath = "$ModelsDir\gemma-4-e2b-it.litertlm"

if (Test-Path $GemmaModelPath) {
    $FileSize = (Get-Item $GemmaModelPath).Length / 1MB
    Write-Host "⚠️  Gemma 模型已存在 ($($FileSize.ToString('0.00')) MB)，跳过下载" -ForegroundColor Yellow
    Write-Host ""
} else {
    Write-Host "🔗 下载地址: <ADDRESS_REMOVED>
    Write-Host "   备用地址: <ADDRESS_REMOVED>
    Write-Host ""
    Write-Host "⚠️  注意: 文件较大 (约 4GB)，下载时间取决于网速" -ForegroundColor Red
    Write-Host ""
    
    $DownloadChoice = Read-Host "是否开始下载? (Y/N)"
    
    if ($DownloadChoice -eq "Y" -or $DownloadChoice -eq "y") {
        try {
            Write-Host "⏳ 正在下载 Gemma 4 E2B 模型..." -ForegroundColor Cyan
            Write-Host "   请耐心等待，不要关闭窗口..." -ForegroundColor Gray
            Write-Host ""
            
            # 尝试从 Hugging Face 下载
            $Url1 = "https://huggingface.co/google/gemma-4-e2b-it/resolve/main/gemma-4-e2b-it.litertlm"
            $Url2 = "https://storage.googleapis.com/ai-edge-litert-lm/gemma-4-e2b-it.litertlm"
            
            $DownloadSuccess = $false
            
            # 尝试 URL1
            Write-Host "   尝试 URL1: Hugging Face..." -ForegroundColor Gray
            try {
                Invoke-WebRequest -Uri $Url1 -OutFile $GemmaModelPath -UseBasicParsing -TimeoutSec 3600
                $DownloadSuccess = $true
            } catch {
                Write-Host "   ❌ URL1 下载失败: $_" -ForegroundColor Red
            }
            
            # 如果失败，尝试 URL2
            if (!$DownloadSuccess) {
                Write-Host "   尝试 URL2: Google Storage..." -ForegroundColor Gray
                try {
                    Invoke-WebRequest -Uri $Url2 -OutFile $GemmaModelPath -UseBasicParsing -TimeoutSec 3600
                    $DownloadSuccess = $true
                } catch {
                    Write-Host "   ❌ URL2 下载失败: $_" -ForegroundColor Red
                }
            }
            
            if ($DownloadSuccess -and (Test-Path $GemmaModelPath)) {
                $FileSize = (Get-Item $GemmaModelPath).Length / 1MB
                Write-Host "✅ Gemma 模型下载成功 ($($FileSize.ToString('0.00')) MB)" -ForegroundColor Green
                Write-Host ""
            } else {
                Write-Host "❌ 所有下载尝试均失败" -ForegroundColor Red
                Write-Host ""
                Write-Host "📝 请手动下载并放入: $GemmaModelPath" -ForegroundColor Yellow
                Write-Host "   1. 访问: https://huggingface.co/google/gemma-4-e2b-it" -ForegroundColor Yellow
                Write-Host "   2. 下载 gemma-4-e2b-it.litertlm" -ForegroundColor Yellow
                Write-Host "   3. 放入: $ModelsDir\" -ForegroundColor Yellow
                Write-Host ""
            }
            
        } catch {
            Write-Host "❌ 下载失败: $_" -ForegroundColor Red
            Write-Host ""
        }
    } else {
        Write-Host "⚠️  跳过 Gemma 模型下载" -ForegroundColor Yellow
        Write-Host ""
    }
}

# ============ 下载视觉模型 (.tflite) ============
Write-Host ""
Write-Host "📦 [2/2] 下载视觉模型 (MobileNetV3)..." -ForegroundColor Yellow
Write-Host "   来源: TensorFlow Hub" -ForegroundColor Gray
Write-Host ""

$VisionModelPath = "$ModelsDir\mobilenet_v3.tflite"

if (Test-Path $VisionModelPath) {
    $FileSize = (Get-Item $VisionModelPath).Length / 1MB
    Write-Host "⚠️  视觉模型已存在 ($($FileSize.ToString('0.00')) MB)，跳过下载" -ForegroundColor Yellow
    Write-Host ""
} else {
    Write-Host "🔗 下载地址: <ADDRESS_REMOVED>
    Write-Host "   备用地址: <ADDRESS_REMOVED>
    Write-Host ""
    
    $DownloadChoice2 = Read-Host "是否开始下载? (Y/N)"
    
    if ($DownloadChoice2 -eq "Y" -or $DownloadChoice2 -eq "y") {
        try {
            Write-Host "⏳ 正在下载 MobileNetV3 模型..." -ForegroundColor Cyan
            Write-Host ""
            
            # 尝试下载
            $Url1 = "https://tfhub.dev/tensorflow/lite-model/mobilenet_v3_1.0_224/1/default/1.tar.gz"
            $Url2 = "https://storage.googleapis.com/tfhub-lite-models/tensorflow/lite-model/mobilenet_v3_1.0_224/1/default/1.tar.gz"
            
            $TempFile = "$env:TEMP\mobilenet_v3.tar.gz"
            
            # 下载压缩包
            Invoke-WebRequest -Uri $Url1 -OutFile $TempFile -UseBasicParsing -TimeoutSec 600
            
            # 解压
            Write-Host "📂 解压模型文件..." -ForegroundColor Cyan
            tar -xzf $TempFile -C $ModelsDir
            
            # 查找 .tflite 文件
            $TfliteFile = Get-ChildItem -Path $ModelsDir -Filter "*.tflite" | Select-Object -First 1
            
            if ($TfliteFile) {
                Rename-Item -Path $TfliteFile.FullName -NewName "mobilenet_v3.tflite" -Force
                Write-Host "✅ 视觉模型下载成功" -ForegroundColor Green
            } else {
                Write-Host "⚠️  未找到 .tflite 文件，请手动下载" -ForegroundColor Yellow
            }
            
            # 清理临时文件
            Remove-Item $TempFile -Force -ErrorAction SilentlyContinue
            
        } catch {
            Write-Host "❌ 下载失败: $_" -ForegroundColor Red
            Write-Host ""
            Write-Host "📝 请手动下载并放入: $VisionModelPath" -ForegroundColor Yellow
            Write-Host "   1. 访问: https://www.tensorflow.org/lite/models" -ForegroundColor Yellow
            Write-Host "   2. 下载 MobileNetV3 .tflite 模型" -ForegroundColor Yellow
            Write-Host "   3. 重命名为 mobilenet_v3.tflite" -ForegroundColor Yellow
            Write-Host "   4. 放入: $ModelsDir\" -ForegroundColor Yellow
            Write-Host ""
        }
    } else {
        Write-Host "⚠️  跳过视觉模型下载" -ForegroundColor Yellow
        Write-Host ""
    }
}

# ============ 验证模型文件 ============
Write-Host ""
Write-Host "🔍 验证模型文件..." -ForegroundColor Cyan
Write-Host ""

$Files = @(
    @{Name="Gemma 4 E2B"; Path=$GemmaModelPath; MinSizeMB=1000},
    @{Name="MobileNetV3"; Path=$VisionModelPath; MinSizeMB=10}
)

$AllValid = $true

foreach ($File in $Files) {
    if (Test-Path $File.Path) {
        $SizeMB = (Get-Item $File.Path).Length / 1MB
        if ($SizeMB -ge $File.MinSizeMB) {
            Write-Host "✅ $($File.Name): $($SizeMB.ToString('0.00')) MB" -ForegroundColor Green
        } else {
            Write-Host "⚠️  $($File.Name): 文件过小 ($($SizeMB.ToString('0.00')) MB)，可能下载不完整" -ForegroundColor Yellow
            $AllValid = $false
        }
    } else {
        Write-Host "❌ $($File.Name): 文件不存在" -ForegroundColor Red
        $AllValid = $false
    }
}

# ============ 生成模型配置 ============
Write-Host ""
Write-Host "📝 生成模型配置文件..." -ForegroundColor Cyan
Write-Host ""

$ConfigPath = "$ModelsDir\model_config.json"

$Config = @{
    gemma = @{
        model_name = "gemma-4-e2b-it"
        model_format = "litertlm"
        model_path = "models/gemma-4-e2b-it.litertlm"
        temperature = 0.1
        max_tokens = 256
        system_prompt = "你是一个专业的 AI 助手"
    }
    vision = @{
        model_name = "mobilenet_v3"
        model_format = "tflite"
        model_path = "models/mobilenet_v3.tflite"
        input_size = 224
        num_channels = 3
        mean = @(0.485, 0.456, 0.406)
        std = @(0.229, 0.224, 0.225)
    }
    inference = @{
        use_gpu = $true
        num_threads = 4
        use_nnapi = $true
    }
} | ConvertTo-Json -Depth 10

$Config | Out-File -FilePath $ConfigPath -Encoding UTF8
Write-Host "✅ 模型配置已生成: $ConfigPath" -ForegroundColor Green
Write-Host ""

# ============ 完成 ============
Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
if ($AllValid) {
    Write-Host "✅ 所有模型下载完成！" -ForegroundColor Green
} else {
    Write-Host "⚠️  部分模型下载失败，请查看上述错误信息" -ForegroundColor Yellow
}
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "📋 下一步:" -ForegroundColor Yellow
Write-Host "   1. 如果模型下载失败，请手动下载并放入:" -ForegroundColor Yellow
Write-Host "      - Gemma 4 E2B: $ModelsDir\" -ForegroundColor Yellow
Write-Host "      - 视觉模型: $ModelsDir\" -ForegroundColor Yellow
Write-Host "   2. 编译项目: cd $ProjectDir && .\gradlew assembleDebug" -ForegroundColor Yellow
Write-Host "   3. 安装到设备: .\gradlew installDebug" -ForegroundColor Yellow
Write-Host "   4. 在手机上运行应用，测试 AI 推理功能" -ForegroundColor Yellow
Write-Host ""
