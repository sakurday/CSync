param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Tasks = @('assembleDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$env:JAVA_HOME) {
    $javaCandidate = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
    if (Test-Path -LiteralPath $javaCandidate) { $env:JAVA_HOME = $javaCandidate }
}
if (!$env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $projectRoot '.build\android-sdk' }
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.build\gradle-home'
$env:TEMP = Join-Path $projectRoot '.build\tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null
$networkArgs = @('-Dorg.gradle.internal.http.connectionTimeout=15000', '-Dorg.gradle.internal.http.socketTimeout=60000')
if ($env:HTTPS_PROXY) {
    $networkProxy = [Uri]$env:HTTPS_PROXY
    $networkArgs += @("-Dhttps.proxyHost=$($networkProxy.Host)", "-Dhttps.proxyPort=$($networkProxy.Port)", "-Dhttp.proxyHost=$($networkProxy.Host)", "-Dhttp.proxyPort=$($networkProxy.Port)")
}
$gradle = Join-Path $projectRoot '.build\gradle-8.11.1\bin\gradle.bat'
if (!(Test-Path -LiteralPath $gradle)) { $gradle = Join-Path $projectRoot 'android\gradlew.bat' }
& $gradle @networkArgs -p (Join-Path $projectRoot 'android') --no-daemon @Tasks
if ($LASTEXITCODE -ne 0) { throw 'Gradle failed' }
