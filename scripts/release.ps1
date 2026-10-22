<#
  本地构建并发布到 GitHub Releases

  用法：
    powershell -ExecutionPolicy Bypass -File scripts\release.ps1 -Tag 1.6.7
    powershell -ExecutionPolicy Bypass -File scripts\release.ps1 -Tag 1.6.7 -Notes "本次更新内容"

  前置条件：
    1. app/build.gradle.kts 里的 versionName 必须与 -Tag 一致，versionCode 手动递增
    2. gh 已登录：C:\Android\gh\bin\gh.exe auth login
#>
param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [string]$Notes = "",
    [string]$Proxy = "http://127.0.0.1:7890",
    [switch]$SkipConfigUpdate
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$env:JAVA_HOME = "C:\Android\jdk\jdk-17.0.20.1+1"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
if ($Proxy) {
    $env:HTTPS_PROXY = $Proxy
    $env:HTTP_PROXY = $Proxy
}
$gh = "C:\Android\gh\bin\gh.exe"
$apksigner = "C:\Android\sdk\build-tools\37.0.0\apksigner.bat"

# 1. 校验版本号一致
$gradleText = Get-Content "app\build.gradle.kts" -Raw
$versionName = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$versionCode = [regex]::Match($gradleText, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if ($versionName -ne $Tag) {
    throw "版本号不一致：build.gradle.kts 为 $versionName（$versionCode），脚本参数为 $Tag。请先修改 versionName。"
}

# 2. 登录检查
& $gh auth status *> $null
if ($LASTEXITCODE -ne 0) {
    throw "gh 未登录。请先执行：`n  $gh auth login`n选择 GitHub.com → HTTPS → Login with a web browser"
}

# 3. 构建
Write-Host "==> 构建 $versionName ($versionCode) ..."
& .\gradlew.bat assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败" }

# 4. 重命名产物为固定名称
$outDir = "app\build\outputs\apk\release"
$dist = "dist"
Remove-Item -Recurse -Force $dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $dist | Out-Null
foreach ($file in Get-ChildItem $outDir -Filter "app-*-release*.apk") {
    $n = $file.Name
    if     ($n -match "arm64-v8a")   { $target = "Mybooks-arm64-v8a.apk" }
    elseif ($n -match "armeabi-v7a") { $target = "Mybooks-armeabi-v7a.apk" }
    elseif ($n -match "x86_64")      { $target = "Mybooks-x86_64.apk" }
    elseif ($n -match "x86")         { $target = "Mybooks-x86.apk" }
    elseif ($n -match "universal")   { $target = "Mybooks-universal.apk" }
    else { continue }
    Copy-Item $file.FullName (Join-Path $dist $target) -Force
    Write-Host "    $target"
}
$apkList = @(Get-ChildItem $dist -Filter *.apk)
if ($apkList.Count -eq 0) { throw "没有找到 APK 产物" }

# 5. 校验签名
Write-Host "==> 校验签名 ..."
& $apksigner verify --print-certs (Join-Path $dist "Mybooks-universal.apk")
if ($LASTEXITCODE -ne 0) { throw "APK 签名校验失败" }

# 6. 创建或更新 Release 并上传
$tagName = "v$Tag"
Write-Host "==> 上传到 GitHub Release $tagName ..."
$assets = @($apkList | ForEach-Object { $_.FullName })
& $gh release view $tagName *> $null
if ($LASTEXITCODE -eq 0) {
    foreach ($asset in $assets) {
        & $gh release upload $tagName $asset --clobber
    }
    if ($LASTEXITCODE -ne 0) { throw "上传附件失败" }
} else {
    if ($Notes -ne "") {
        & $gh release create $tagName @assets --title "Mybooks $tagName" --notes $Notes
    } else {
        & $gh release create $tagName @assets --title "Mybooks $tagName" --generate-notes
    }
    if ($LASTEXITCODE -ne 0) { throw "创建 Release 失败" }
}

# 7. 同步在线更新配置
if (-not $SkipConfigUpdate) {
    $configPath = "docs\app-config.json"
    $config = Get-Content $configPath -Raw
    $config = [regex]::Replace($config, '"latest_version_code":\s*\d+', '"latest_version_code": ' + $versionCode)
    $config = [regex]::Replace($config, '"latest_version_name":\s*"[^"]*"', '"latest_version_name": "' + $versionName + '"')
    [IO.File]::WriteAllText((Join-Path (Get-Location) $configPath), $config, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "==> 已更新 docs/app-config.json（$versionName / $versionCode）"

    git add $configPath
    git commit -m "release: $tagName" *> $null
    $proxyArgs = @()
    if ($Proxy) { $proxyArgs = @("-c", "http.proxy=$Proxy", "-c", "https.proxy=$Proxy") }
    git @proxyArgs push origin main
    if ($LASTEXITCODE -ne 0) { Write-Warning "配置推送失败，请手动执行 git push origin main" }
}

Write-Host ""
Write-Host "完成：https://github.com/shyshineinn/Mybooks/releases/tag/$tagName"
