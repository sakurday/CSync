param([switch]$Build, [switch]$AcceptLicenses)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = Join-Path $projectRoot '.build'
$sdkRoot = Join-Path $toolsRoot 'android-sdk'
$gradleRoot = Join-Path $toolsRoot 'gradle-8.11.1'
New-Item -ItemType Directory -Force -Path $toolsRoot,$sdkRoot | Out-Null

function Get-Archive([string]$Url, [string]$Destination) {
    if (!(Test-Path -LiteralPath $Destination)) { Invoke-WebRequest -Uri $Url -OutFile $Destination }
}
if (!(Test-Path -LiteralPath (Join-Path $gradleRoot 'bin\gradle.bat'))) {
    $zip = Join-Path $toolsRoot 'gradle.zip'
    Get-Archive 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip' $zip
    Expand-Archive -LiteralPath $zip -DestinationPath $toolsRoot -Force
}
if (!(Test-Path -LiteralPath (Join-Path $sdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'))) {
    $zip = Join-Path $toolsRoot 'android-commandline.zip'
    Get-Archive 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' $zip
    $extractRoot = Join-Path $toolsRoot 'android-commandline'
    Expand-Archive -LiteralPath $zip -DestinationPath $extractRoot -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $sdkRoot 'cmdline-tools') | Out-Null
    Move-Item -LiteralPath (Join-Path $extractRoot 'cmdline-tools') -Destination (Join-Path $sdkRoot 'cmdline-tools\latest')
}
$javaCandidate = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
if (Test-Path -LiteralPath $javaCandidate) { $env:JAVA_HOME = $javaCandidate }
$env:TEMP = Join-Path $toolsRoot 'tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null
$env:ANDROID_HOME = $sdkRoot
$env:GRADLE_USER_HOME = Join-Path $toolsRoot 'gradle-home'
$sdkManager = Join-Path $sdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
$sdkNetworkArgs = @()
$gradleNetworkArgs = @()
if ($env:HTTPS_PROXY) {
    $networkProxy = [Uri]$env:HTTPS_PROXY
    $sdkNetworkArgs = @('--proxy=http', "--proxy_host=$($networkProxy.Host)", "--proxy_port=$($networkProxy.Port)")
    $gradleNetworkArgs = @("-Dhttps.proxyHost=$($networkProxy.Host)", "-Dhttps.proxyPort=$($networkProxy.Port)", "-Dhttp.proxyHost=$($networkProxy.Host)", "-Dhttp.proxyPort=$($networkProxy.Port)")
}
if ($AcceptLicenses) { 1..20 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdkRoot" @sdkNetworkArgs --licenses | Out-Null }
& $sdkManager "--sdk_root=$sdkRoot" @sdkNetworkArgs 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
if ($LASTEXITCODE -ne 0) { throw 'Android SDK installation failed. Run sdkmanager --licenses if requested.' }
if (!(Test-Path -LiteralPath (Join-Path $sdkRoot 'platforms\android-35\android.jar'))) { throw 'Android platform missing. Run sdkmanager --licenses to accept SDK terms, then retry.' }
if ($Build) {
    & (Join-Path $gradleRoot 'bin\gradle.bat') @gradleNetworkArgs -p (Join-Path $projectRoot 'android') --no-daemon assembleDebug testDebugUnitTest lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
}
