Add-Type -AssemblyName System.Net.Http
[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12

# Proxy settings
$proxyUrl = "http://127.0.0.1:7897"
$proxy = New-Object System.Net.WebProxy($proxyUrl)

# Step 1: Create Release via Invoke-RestMethod
$headers = @{
    "Authorization" = "Bearer $env:GITHUB_TOKEN"
    "Accept" = "application/vnd.github+json"
    "User-Agent" = "VisionLink-Android"
}

$releaseBody = @"
## VisionLink Android v5.9.3

### 修复内容

#### 1. YOLO 模型初始化失败修复
- 添加显式 CPU 代理（Delegate.CPU），失败后回退 GPU
- 初始化前验证 assets 中模型文件是否存在
- 关键日志改用 Log.w/Log.e，避免 release 构建中被 ProGuard 移除

#### 2. Gemma 4 模型检测修复
- 大幅扩展 .litertlm 模型文件搜索路径
  - 新增 context.filesDir/models/、getExternalFilesDir/Download/、/sdcard/ 根目录
  - 新增 Google AI Edge Gallery 存储路径
  - 递归深度从 2 层增加到 3 层
- 新增文件选择器：通过 更多 > 选择模型文件 手动指定 .litertlm 模型
  - 自动复制到应用内部存储并切换到 Gemma 4 引擎
  - 支持语音引导操作流程

#### 3. StepFun API 断断续续修复
- 添加 ConnectionPool 保持长连接复用
- 启用 retryOnConnectionFailure
- 连接超时从 15s 增加到 20s，读取超时从 60s 增加到 90s

#### 4. 自动更新无法检测修复（关键）
- 修复 parseVersionCode 逻辑：
  - 旧版: major*10+minor（不含 patch），5.9.2 to 59
  - 新版: major*100+minor*10+patch，5.9.2 to 592
  - versionCode 同步更新为 593
  - 此前因 59 < 61 导致永远检测不到更新

#### 5. AICore 诊断信息优化
- 诊断结果改为中文显示
- 明确提示 AICore 为可选功能，不可用不影响正常使用

### 下载
- 下载 APK 安装即可，无需卸载旧版本
"@

$bodyObj = @{
    tag_name = "v5.9.3"
    name = "v5.9.3"
    body = $releaseBody
    draft = $false
    prerelease = $false
}
$bodyJson = $bodyObj | ConvertTo-Json -Depth 5

Write-Host "Creating GitHub Release v5.9.3..."
try {
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/cpufreestyle/visionlink-android/releases" -Method POST -Headers $headers -Body $bodyJson -ContentType "application/json; charset=utf-8" -Proxy $proxyUrl -ProxyUseDefaultCredentials
    Write-Host "Release created! ID: $($release.id)"
    Write-Host "URL: $($release.html_url)"
    $releaseId = $release.id
} catch {
    Write-Host "ERROR creating release: $($_.Exception.Message)"
    if ($_.ErrorDetails) { Write-Host $_.ErrorDetails.Message }
    exit 1
}

# Step 2: Upload APK using HttpClient (for large file support with proxy)
Write-Host ""
Write-Host "Uploading APK..."

$apkPath = "d:/qclaw-workspace/visionlink-android/app/build/outputs/apk/release/app-release.apk"
$apkBytes = [System.IO.File]::ReadAllBytes($apkPath)
$sizeMB = [math]::Round($apkBytes.Length / 1MB, 1)
Write-Host "APK size: $sizeMB MB"

$httpHandler = New-Object System.Net.Http.HttpClientHandler
$httpHandler.Proxy = $proxy
$httpHandler.ServerCertificateCustomValidationCallback = [System.Net.Http.HttpClientHandler]::DangerousAcceptAnyServerCertificateValidator

$httpClient = New-Object System.Net.Http.HttpClient($httpHandler)
$httpClient.Timeout = [TimeSpan]::FromMinutes(15)
$httpClient.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $env:GITHUB_TOKEN)
$httpClient.DefaultRequestHeaders.Accept.Add((New-Object System.Net.Http.Headers.MediaTypeWithQualityHeaderValue("application/vnd.github+json")))
$httpClient.DefaultRequestHeaders.Add("User-Agent", "VisionLink-Android")

$apkContent = New-Object System.Net.Http.ByteArrayContent($apkBytes)
$apkContent.Headers.ContentType = New-Object System.Net.Http.Headers.MediaTypeHeaderValue("application/vnd.android.package-archive")

$uploadUrl = "https://uploads.github.com/repos/cpufreestyle/visionlink-android/releases/$releaseId/assets?name=visionlink-android-v5.9.3.apk"
Write-Host "Uploading to: $uploadUrl"

$uploadResp = $httpClient.PostAsync($uploadUrl, $apkContent).Result
$uploadBody = $uploadResp.Content.ReadAsStringAsync().Result
Write-Host "Upload Status: $($uploadResp.StatusCode)"

if ($uploadResp.StatusCode -eq 201) {
    $uploadObj = $uploadBody | ConvertFrom-Json
    Write-Host "APK Download URL: $($uploadObj.browser_download_url)"
    Write-Host ""
    Write-Host "=== SUCCESS ==="
    Write-Host "Release: $($release.html_url)"
    Write-Host "APK: $($uploadObj.browser_download_url)"
} else {
    Write-Host "ERROR: Failed to upload APK"
    Write-Host $uploadBody
}

$httpClient.Dispose()
