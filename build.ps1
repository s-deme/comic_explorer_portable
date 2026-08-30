[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')]
    [string]$Configuration = 'Debug',
    [string]$OutputPath = 'dist/comic-explorer.apk',
    [string]$AndroidSdkRoot,
    [string]$JavaHome
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$isWindowsPlatform = [System.IO.Path]::DirectorySeparatorChar -eq '\'
$executableSuffix = if ($isWindowsPlatform) { '.exe' } else { '' }

function Assert-LastExitCode([string]$message) {
    if ($LASTEXITCODE -ne 0) { throw $message }
}

function Resolve-AndroidSdkRoot([string]$explicitRoot) {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($explicitRoot)) {
        $candidates.Add($explicitRoot)
    } else {
        if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) { $candidates.Add($env:ANDROID_SDK_ROOT) }
        if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) { $candidates.Add($env:ANDROID_HOME) }
        if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { $candidates.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk')) }
    }

    foreach ($candidate in $candidates) {
        $resolved = [System.IO.Path]::GetFullPath($candidate)
        if (Test-Path -LiteralPath $resolved -PathType Container) { return $resolved }
    }
    throw 'Android SDKが見つかりません。ANDROID_SDK_ROOTを設定するか、-AndroidSdkRootで指定してください。'
}

function Get-JavaMajorVersion([string]$candidateHome) {
    if ([string]::IsNullOrWhiteSpace($candidateHome)) { return 0 }
    $javaName = if ($isWindowsPlatform) { 'java.exe' } else { 'java' }
    $candidateJava = Join-Path $candidateHome "bin\$javaName"
    if (-not (Test-Path -LiteralPath $candidateJava -PathType Leaf)) { return 0 }
    $versionText = (& $candidateJava -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { return 0 }
    if ($versionText -match 'version\s+"1\.(\d+)') { return [int]$Matches[1] }
    if ($versionText -match 'version\s+"(\d+)') { return [int]$Matches[1] }
    return 0
}

function Resolve-JavaHome([string]$explicitHome) {
    if (-not [string]::IsNullOrWhiteSpace($explicitHome)) {
        $resolved = [System.IO.Path]::GetFullPath($explicitHome)
        if ((Get-JavaMajorVersion $resolved) -lt 21) { throw '-JavaHomeにはJDK 21以降を指定してください。' }
        return $resolved
    }

    $candidates = [System.Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { $candidates.Add($env:JAVA_HOME) }
    if ($isWindowsPlatform) {
        $candidates.Add('C:\Program Files\Android\Android Studio\jbr')
        if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
            $jdksRoot = Join-Path $env:USERPROFILE '.jdks'
            if (Test-Path -LiteralPath $jdksRoot -PathType Container) {
                Get-ChildItem -LiteralPath $jdksRoot -Directory | Sort-Object Name -Descending | ForEach-Object { $candidates.Add($_.FullName) }
            }
        }
        if (-not [string]::IsNullOrWhiteSpace($env:ProgramFiles)) {
            $javaRoot = Join-Path $env:ProgramFiles 'Java'
            if (Test-Path -LiteralPath $javaRoot -PathType Container) {
                Get-ChildItem -LiteralPath $javaRoot -Directory | Sort-Object Name -Descending | ForEach-Object { $candidates.Add($_.FullName) }
            }
        }
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        $resolved = [System.IO.Path]::GetFullPath($candidate)
        if ((Get-JavaMajorVersion $resolved) -ge 21) { return $resolved }
    }
    throw 'JDK 21以降が見つかりません。JAVA_HOMEを設定するか、-JavaHomeで指定してください。'
}

function Resolve-OutputPath([string]$requestedPath) {
    if ([string]::IsNullOrWhiteSpace($requestedPath)) { throw 'OutputPathは空にできません。' }
    if ([System.IO.Path]::IsPathRooted($requestedPath)) { return [System.IO.Path]::GetFullPath($requestedPath) }
    return [System.IO.Path]::GetFullPath((Join-Path $projectRoot $requestedPath))
}

$resolvedSdkRoot = Resolve-AndroidSdkRoot $AndroidSdkRoot
$resolvedJavaHome = Resolve-JavaHome $JavaHome
$buildTools = Join-Path $resolvedSdkRoot 'build-tools\36.0.0'
$sdkPlatformJar = Join-Path $resolvedSdkRoot 'platforms\android-35\android.jar'
$javaRelativePath = if ($isWindowsPlatform) { 'bin\java.exe' } else { 'bin/java' }
$javacRelativePath = if ($isWindowsPlatform) { 'bin\javac.exe' } else { 'bin/javac' }
$keytoolRelativePath = if ($isWindowsPlatform) { 'bin\keytool.exe' } else { 'bin/keytool' }
$apksignerName = if ($isWindowsPlatform) { 'apksigner.bat' } else { 'apksigner' }
$java = Join-Path $resolvedJavaHome $javaRelativePath
$javac = Join-Path $resolvedJavaHome $javacRelativePath
$keytool = Join-Path $resolvedJavaHome $keytoolRelativePath
$aapt2 = Join-Path $buildTools "aapt2$executableSuffix"
$aapt = Join-Path $buildTools "aapt$executableSuffix"
$zipalign = Join-Path $buildTools "zipalign$executableSuffix"
$apksigner = Join-Path $buildTools $apksignerName

foreach ($requiredPath in @($buildTools, $sdkPlatformJar, $java, $javac, $keytool, $aapt2, $aapt, $zipalign, $apksigner)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) { throw "必要なビルドツールがありません: $requiredPath" }
}

if ($Configuration -eq 'Release') {
    $requiredVariables = @('ANDROID_KEYSTORE_PATH', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD')
    foreach ($variableName in $requiredVariables) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($variableName))) {
            throw "Release署名に必要な環境変数がありません: $variableName"
        }
    }
    $keystore = [System.IO.Path]::GetFullPath($env:ANDROID_KEYSTORE_PATH)
    if (-not (Test-Path -LiteralPath $keystore -PathType Leaf)) { throw 'Release署名用keystoreが見つかりません。' }
    $keyAlias = $env:ANDROID_KEY_ALIAS
    $keystorePassword = 'env:ANDROID_KEYSTORE_PASSWORD'
    $keyPassword = 'env:ANDROID_KEY_PASSWORD'
} else {
    $keystore = Join-Path $projectRoot '.comic-explorer-debug.keystore'
    $keyAlias = 'comic-explorer'
    $keystorePassword = 'pass:android'
    $keyPassword = 'pass:android'
    if (-not (Test-Path -LiteralPath $keystore -PathType Leaf)) {
        & $keytool -genkeypair -keystore $keystore -storepass android -keypass android -alias $keyAlias -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Comic Explorer, OU=Personal, O=Local, L=Tokyo, C=JP'
        Assert-LastExitCode 'Debug keystore could not be generated.'
    }
}

$buildRoot = Join-Path $projectRoot 'build'
$classes = Join-Path $buildRoot 'classes'
$dex = Join-Path $buildRoot 'dex'
$unsignedApk = Join-Path $buildRoot 'unsigned.apk'
$alignedApk = Join-Path $buildRoot 'aligned.apk'
$outputApk = Resolve-OutputPath $OutputPath
$outputDirectory = Split-Path -Parent $outputApk

Remove-Item -LiteralPath $buildRoot -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classes, $dex, $outputDirectory -Force | Out-Null
$platformJar = Join-Path $buildRoot 'android.jar'
Copy-Item -LiteralPath $sdkPlatformJar -Destination $platformJar

& $aapt2 compile --dir (Join-Path $projectRoot 'app\src\main\res') -o (Join-Path $buildRoot 'resources.zip')
Assert-LastExitCode 'Android resources could not be compiled.'
& $aapt2 link -I $platformJar --manifest (Join-Path $projectRoot 'AndroidManifest.xml') --min-sdk-version 29 --target-sdk-version 35 -o $unsignedApk (Join-Path $buildRoot 'resources.zip')
Assert-LastExitCode 'Android resources could not be linked.'

$sources = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app\src\main\java') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
& $javac -source 8 -target 8 -encoding UTF-8 -classpath $platformJar -d $classes $sources
Assert-LastExitCode 'Java sources could not be compiled.'
$classFiles = Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName
& $java -cp (Join-Path $buildTools 'lib\d8.jar') com.android.tools.r8.D8 --min-api 29 --lib $platformJar --output $dex $classFiles
Assert-LastExitCode 'DEX generation failed.'
Copy-Item -LiteralPath (Join-Path $dex 'classes.dex') -Destination (Join-Path $buildRoot 'classes.dex')
Push-Location $buildRoot
try {
    & $aapt add $unsignedApk 'classes.dex'
    Assert-LastExitCode 'classes.dex could not be packaged.'
} finally {
    Pop-Location
}
& $zipalign -f -p 4 $unsignedApk $alignedApk
Assert-LastExitCode 'APK alignment failed.'

& $apksigner sign --ks $keystore --ks-key-alias $keyAlias --ks-pass $keystorePassword --key-pass $keyPassword --v2-signing-enabled true --v3-signing-enabled true --out $outputApk $alignedApk
Assert-LastExitCode 'APK signing failed.'
& $apksigner verify --verbose $outputApk
Assert-LastExitCode 'APK signature verification failed.'

$permissionOutput = & $aapt2 dump permissions $outputApk 2>&1
Assert-LastExitCode 'APK permissions could not be inspected.'
$unexpectedPermissions = @($permissionOutput | Where-Object { $_ -match '^uses-permission' })
if ($unexpectedPermissions.Count -gt 0) {
    throw "Unexpected Android permissions were found: $($unexpectedPermissions -join ', ')"
}

Write-Host "APK created ($Configuration): $outputApk"
Write-Host 'Signature and permission checks passed.'
