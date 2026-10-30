param([switch]$SkipAndroid)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$env:TEMP = Join-Path $projectRoot '.build\tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP,(Join-Path $projectRoot 'dist') | Out-Null
& cargo test --workspace --locked
if ($LASTEXITCODE -ne 0) { throw 'Rust tests failed' }
& cargo build --release --locked
if ($LASTEXITCODE -ne 0) { throw 'Windows build failed' }
Copy-Item -LiteralPath (Join-Path $projectRoot 'target\release\CSync.exe') -Destination (Join-Path $projectRoot 'dist\CSync.exe') -Force
if (!$SkipAndroid) {
    if (!$env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $projectRoot '.build\android-sdk' }
    if (!$env:JAVA_HOME) {
        $javaCandidate = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
        if (Test-Path -LiteralPath $javaCandidate) { $env:JAVA_HOME = $javaCandidate }
    }
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.build\gradle-home'
    $gradle = Join-Path $projectRoot '.build\gradle-8.11.1\bin\gradle.bat'
    if (!(Test-Path -LiteralPath $gradle)) { throw 'Run scripts/bootstrap-android.ps1 first, or open android/ in Android Studio.' }
    $networkArgs = @()
    if ($env:HTTPS_PROXY) {
        $networkProxy = [Uri]$env:HTTPS_PROXY
        $networkArgs = @("-Dhttps.proxyHost=$($networkProxy.Host)", "-Dhttps.proxyPort=$($networkProxy.Port)", "-Dhttp.proxyHost=$($networkProxy.Host)", "-Dhttp.proxyPort=$($networkProxy.Port)")
    }
    # Debug APK is installable for development. Production signing is intentionally
    # owned by the developer; the repository contains no shared release private key.
    & $gradle @networkArgs -p android --no-daemon assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'Android build/check failed' }
    Copy-Item -LiteralPath 'android\app\build\outputs\apk\debug\app-debug.apk' -Destination 'dist\CSync-android-debug.apk' -Force
}
Write-Output "Build artifacts: $(Join-Path $projectRoot 'dist')"
