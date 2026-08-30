$ErrorActionPreference = 'Stop'

$projectRoot = $PSScriptRoot
$sdkRoot = 'C:\Users\yaman\AppData\Local\Android\Sdk'
$buildTools = Join-Path $sdkRoot 'build-tools\36.0.0'
$sdkPlatformJar = Join-Path $sdkRoot 'platforms\android-35\android.jar'
$javaHome = 'C:\Users\yaman\.jdks\jbr-21.0.11'
$java = Join-Path $javaHome 'bin\java.exe'
$javac = Join-Path $javaHome 'bin\javac.exe'
$keytool = Join-Path $javaHome 'bin\keytool.exe'
$buildRoot = Join-Path $projectRoot 'build'
$classes = Join-Path $buildRoot 'classes'
$dex = Join-Path $buildRoot 'dex'
$dist = Join-Path $projectRoot 'dist'
$unsignedApk = Join-Path $buildRoot 'unsigned.apk'
$alignedApk = Join-Path $buildRoot 'aligned.apk'
$outputApk = Join-Path $dist 'comic-explorer.apk'
$keystore = Join-Path $projectRoot '.comic-explorer-debug.keystore'

foreach ($path in @($sdkRoot, $buildTools, $sdkPlatformJar, $java, $javac, $keytool)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "必要なビルドツールがありません: $path" }
}

Remove-Item -LiteralPath $buildRoot -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classes, $dex, $dist -Force | Out-Null
$platformJar = Join-Path $buildRoot 'android.jar'
Copy-Item -LiteralPath $sdkPlatformJar -Destination $platformJar

& (Join-Path $buildTools 'aapt2.exe') compile --dir (Join-Path $projectRoot 'app\src\main\res') -o (Join-Path $buildRoot 'resources.zip')
if ($LASTEXITCODE -ne 0) { throw 'Android resources could not be compiled.' }
& (Join-Path $buildTools 'aapt2.exe') link -I $platformJar --manifest (Join-Path $projectRoot 'AndroidManifest.xml') --min-sdk-version 29 --target-sdk-version 35 -o $unsignedApk (Join-Path $buildRoot 'resources.zip')
if ($LASTEXITCODE -ne 0) { throw 'Android resources could not be linked.' }

$sources = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app\src\main\java') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
& $javac -source 8 -target 8 -encoding UTF-8 -classpath $platformJar -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Java sources could not be compiled.' }
$classFiles = Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName
& $java -cp (Join-Path $buildTools 'lib\d8.jar') com.android.tools.r8.D8 --min-api 29 --lib $platformJar --output $dex $classFiles
if ($LASTEXITCODE -ne 0) { throw 'DEX generation failed.' }
Copy-Item -LiteralPath (Join-Path $dex 'classes.dex') -Destination (Join-Path $buildRoot 'classes.dex')
Push-Location $buildRoot
& (Join-Path $buildTools 'aapt.exe') add $unsignedApk 'classes.dex'
Pop-Location
if ($LASTEXITCODE -ne 0) { throw 'classes.dex could not be packaged.' }
& (Join-Path $buildTools 'zipalign.exe') -f -p 4 $unsignedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }

if (-not (Test-Path -LiteralPath $keystore)) {
    & $keytool -genkeypair -keystore $keystore -storepass android -keypass android -alias comic-explorer -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Comic Explorer, OU=Personal, O=Local, L=Tokyo, C=JP'
}
& (Join-Path $buildTools 'apksigner.bat') sign --ks $keystore --ks-key-alias comic-explorer --ks-pass pass:android --key-pass pass:android --v2-signing-enabled true --v3-signing-enabled true --out $outputApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
& (Join-Path $buildTools 'apksigner.bat') verify --verbose $outputApk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
Write-Host "APK created: $outputApk"
