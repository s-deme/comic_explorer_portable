[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Debug',
    [string]$OutputPath = 'dist/comic-explorer.apk',
    [string]$OutputDirectory,
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

$allowedPermissions = @('android.permission.INTERNET',
    'jp.yaman.comicexplorer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION') # AndroidX private receivers; signature-protected.

function Copy-AndVerifyApk {
    param(
        [Parameter(Mandatory = $true)][string]$SourceApk,
        [Parameter(Mandatory = $true)][string]$DestinationApk,
        [string]$ExpectedAbi,
        [switch]$WriteChecksum
    )

    if (-not (Test-Path -LiteralPath $SourceApk -PathType Leaf)) {
        throw "Gradleが出力したAPKが見つかりません: $SourceApk"
    }
    Copy-Item -LiteralPath $SourceApk -Destination $DestinationApk -Force
    & $apksigner verify --verbose $DestinationApk
    if ($LASTEXITCODE -ne 0) { throw "APK署名の検証に失敗しました: $DestinationApk" }
    $permissionOutput = & $aapt2 dump permissions $DestinationApk 2>&1
    if ($LASTEXITCODE -ne 0) { throw "APK権限の検査に失敗しました: $DestinationApk" }
    $unexpectedPermissions = @($permissionOutput | Where-Object {
        $_ -match "^uses-permission: name='([^']+)'" -and $Matches[1] -notin $allowedPermissions
    })
    if ($unexpectedPermissions.Count -gt 0) {
        throw "Unexpected Android permissions were found in ${DestinationApk}: $($unexpectedPermissions -join ', ')"
    }
    if ($ExpectedAbi) {
        $badging = & $aapt2 dump badging $DestinationApk 2>&1
        $nativeAbi = [regex]::Match(($badging -join "`n"), "(?m)^native-code: '([^']+)'$").Groups[1].Value
        if ($LASTEXITCODE -ne 0 -or $nativeAbi -ne $ExpectedAbi) {
            throw "APK ABIの検査に失敗しました: $DestinationApk (expected $ExpectedAbi)"
        }
    }
    if ($WriteChecksum) {
        $hash = (Get-FileHash -LiteralPath $DestinationApk -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $([System.IO.Path]::GetFileName($DestinationApk))" | Set-Content -LiteralPath "$DestinationApk.sha256" -Encoding ascii
    }
    Write-Host "APK created ($Configuration): $DestinationApk"
}

if ($Configuration -eq 'Release') {
    $releaseOutputPath = if ([System.IO.Path]::IsPathRooted($OutputPath)) {
        [System.IO.Path]::GetFullPath($OutputPath)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path $projectRoot $OutputPath))
    }
    $releaseDirectory = if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
        Split-Path -Parent $releaseOutputPath
    } elseif ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
        [System.IO.Path]::GetFullPath($OutputDirectory)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDirectory))
    }
    New-Item -ItemType Directory -Path $releaseDirectory -Force | Out-Null
    foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')) {
        $sourceApk = Join-Path $projectRoot "app\build\outputs\apk\$variant\app-$abi-$variant.apk"
        $outputApk = Join-Path $releaseDirectory "comic-explorer-$abi.apk"
        Copy-AndVerifyApk -SourceApk $sourceApk -DestinationApk $outputApk -ExpectedAbi $abi -WriteChecksum
    }
    Write-Host 'Release ABI APK signature, permission, ABI, and SHA-256 checks passed. arm64-v8a is the standard distribution APK.'
} else {
    $sourceApk = Join-Path $projectRoot "app\build\outputs\apk\$variant\app-universal-$variant.apk"
    $outputApk = if ([System.IO.Path]::IsPathRooted($OutputPath)) {
        [System.IO.Path]::GetFullPath($OutputPath)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path $projectRoot $OutputPath))
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $outputApk) -Force | Out-Null
    Copy-AndVerifyApk -SourceApk $sourceApk -DestinationApk $outputApk
    Write-Host 'Signature and permission checks passed.'
}
