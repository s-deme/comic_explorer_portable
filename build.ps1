[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Debug',
    [string]$OutputPath = 'dist/comic-explorer.apk',
    [string]$AndroidSdkRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$isWindowsPlatform = [System.IO.Path]::DirectorySeparatorChar -eq '\'
$gradleWrapperName = if ($isWindowsPlatform) { 'gradlew.bat' } else { 'gradlew' }
$gradleWrapper = Join-Path $projectRoot $gradleWrapperName
if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
    throw 'Gradle Wrapperが見つかりません。プロジェクトのルートで実行してください。'
}

$variant = $Configuration.ToLowerInvariant()
& $gradleWrapper ":app:assemble$Configuration" '--no-daemon'
if ($LASTEXITCODE -ne 0) { throw "Gradle $Configuration buildに失敗しました。" }

$sourceApk = Join-Path $projectRoot "app\build\outputs\apk\$variant\app-$variant.apk"
if (-not (Test-Path -LiteralPath $sourceApk -PathType Leaf)) {
    throw "Gradleが出力したAPKが見つかりません: $sourceApk"
}

$outputApk = if ([System.IO.Path]::IsPathRooted($OutputPath)) {
    [System.IO.Path]::GetFullPath($OutputPath)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $projectRoot $OutputPath))
}
$outputDirectory = Split-Path -Parent $outputApk
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
Copy-Item -LiteralPath $sourceApk -Destination $outputApk -Force

$sdkRoot = if (-not [string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    [System.IO.Path]::GetFullPath($AndroidSdkRoot)
} elseif (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
    [System.IO.Path]::GetFullPath($env:ANDROID_SDK_ROOT)
} elseif (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
    [System.IO.Path]::GetFullPath($env:ANDROID_HOME)
} elseif (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
    Join-Path $env:LOCALAPPDATA 'Android\Sdk'
} else {
    throw 'Android SDKが見つかりません。ANDROID_SDK_ROOTを設定するか、-AndroidSdkRootで指定してください。'
}

$buildTools = Join-Path $sdkRoot 'build-tools\36.0.0'
$apksignerName = if ($isWindowsPlatform) { 'apksigner.bat' } else { 'apksigner' }
$aapt2Name = if ($isWindowsPlatform) { 'aapt2.exe' } else { 'aapt2' }
$apksigner = Join-Path $buildTools $apksignerName
$aapt2 = Join-Path $buildTools $aapt2Name
foreach ($tool in @($apksigner, $aapt2)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "必要なAndroid build-toolsがありません: $tool"
    }
}

& $apksigner verify --verbose $outputApk
if ($LASTEXITCODE -ne 0) { throw 'APK署名の検証に失敗しました。' }
$permissionOutput = & $aapt2 dump permissions $outputApk 2>&1
if ($LASTEXITCODE -ne 0) { throw 'APK権限の検査に失敗しました。' }
$unexpectedPermissions = @($permissionOutput | Where-Object { $_ -match '^uses-permission' })
if ($unexpectedPermissions.Count -gt 0) {
    throw "Unexpected Android permissions were found: $($unexpectedPermissions -join ', ')"
}

Write-Host "APK created ($Configuration): $outputApk"
Write-Host 'Signature and permission checks passed.'
