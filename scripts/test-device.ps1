param([string]$LanHost = '127.0.0.1', [switch]$SkipMainInstall, [switch]$SkipTestInstall)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$env:TEMP = Join-Path $projectRoot '.build\tmp'
$env:TMP = $env:TEMP
$env:CSYNC_TEST_HOST = $LanHost
$env:CSYNC_TEST_RUN = [Guid]::NewGuid().ToString()
$serverRoot = Join-Path $projectRoot '.build\device-server'
New-Item -ItemType Directory -Force -Path $serverRoot | Out-Null
# Only explicit files belonging to this test run are removed.
foreach ($name in @('ready', 'device-result.json')) {
    $path = Join-Path $serverRoot $name
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path }
}
if (!$SkipMainInstall) {
    & adb install -r (Join-Path $projectRoot 'android\app\build\outputs\apk\debug\app-debug.apk')
    if ($LASTEXITCODE -ne 0) { throw 'APK installation failed' }
}
if (!$SkipTestInstall) {
    & adb install -r (Join-Path $projectRoot 'android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')
    if ($LASTEXITCODE -ne 0) { throw 'Test APK installation failed' }
}
& adb reverse tcp:45877 tcp:45877
& adb shell run-as io.csync.app rm -f files/device-result.json files/device-diagnostic.json
$cargo = (Get-Command cargo).Source
$server = Start-Process -FilePath $cargo -ArgumentList @('test','-p','csync-windows','android_device_server','--offline','--','--ignored','--nocapture') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $serverRoot 'server.stdout.log') -RedirectStandardError (Join-Path $serverRoot 'server.stderr.log')
try {
    $deadline = (Get-Date).AddSeconds(60)
    while (!(Test-Path -LiteralPath (Join-Path $serverRoot 'ready'))) {
        if ((Get-Date) -gt $deadline -or $server.HasExited) { throw 'Test server failed to start' }
        Start-Sleep -Milliseconds 200
    }
    & adb shell am instrument -w -e class io.csync.app.DeviceIntegrationTest -e host $LanHost -e runId $env:CSYNC_TEST_RUN io.csync.app.test/androidx.test.runner.AndroidJUnitRunner
    $result = & adb shell run-as io.csync.app cat files/device-result.json
    if ($LASTEXITCODE -ne 0) {
        & adb shell run-as io.csync.app cat files/device-diagnostic.json
        throw 'Android integration test failed; inspect instrumentation output'
    }
    [System.IO.File]::WriteAllText((Join-Path $serverRoot 'device-result.json'), ($result -join "`n"), [System.Text.UTF8Encoding]::new($false))
    $server.WaitForExit(15000) | Out-Null
    if (!$server.HasExited -or $server.ExitCode -ne 0) { throw 'Test server assertions failed' }
    Write-Output $result
} finally {
    & adb reverse --remove tcp:45877
    if (!$server.HasExited) {
        [System.IO.File]::WriteAllText((Join-Path $serverRoot 'device-result.json'), '{"success":false}', [System.Text.UTF8Encoding]::new($false))
        $server.WaitForExit(5000) | Out-Null
        if (!$server.HasExited) { Stop-Process -Id $server.Id -ErrorAction SilentlyContinue }
    }
}
