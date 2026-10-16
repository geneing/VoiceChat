param(
    [string]$InputPath = "test_data/Walking .m4a",
    [string]$DeviceSerial,
    [switch]$SmartTurn,
    [string]$OutputDirectory,
    [switch]$SkipBuild,
    [switch]$SkipModelPush
)

# Drives the on-device STT pipeline harness from a recorded clip. It installs the
# debug app + instrumentation APK, stages the pinned Parakeet-EOU model (unless
# already present) and the clip into app-private storage, then runs
# SttPipelineHarnessInstrumentedTest, which replays the clip through the real
# capture -> VAD/onset -> Parakeet-EOU STT -> bounded endpointing pipeline with
# optional Smart Turn. It copies the content-free timing summary and the per-stage
# timeline back to the host.
#
# The clip may be WAV or M4A; M4A is decoded on the device, so no host conversion
# is required. Nothing is committed; the results are local artifacts.
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$inputCandidate = if ([System.IO.Path]::IsPathRooted($InputPath)) { $InputPath } else { Join-Path $repoRoot $InputPath }
$inputResolved = (Resolve-Path -LiteralPath $inputCandidate).Path
$input = Get-Item -LiteralPath $inputResolved
if ($input.Extension.ToLowerInvariant() -notin @(".wav", ".m4a")) {
    throw "The harness input must be a .wav or .m4a file."
}

$runId = [guid]::NewGuid().ToString("N")
$appId = "com.voicechat.agent"
$instrumentationComponent = "$appId.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = "com.voicechat.agent.voice.SttPipelineHarnessInstrumentedTest"
$remoteRunDirectory = "/data/user/0/$appId/cache/voicechat-stt-harness/$runId"
$deviceTempDirectory = "/data/local/tmp/voicechat-stt-harness-$runId"
$adb = Join-Path (Join-Path $env:LOCALAPPDATA "Android\Sdk") "platform-tools\adb.exe"
$gradle = Join-Path $repoRoot "gradlew.bat"
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk"

if (-not (Test-Path -LiteralPath $adb)) { throw "Android SDK ADB was not found at '$adb'." }
if (-not (Test-Path -LiteralPath $gradle)) { throw "Gradle wrapper was not found at '$gradle'." }

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repoRoot "build/stt-harness/$runId"
} elseif (-not [System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $repoRoot $OutputDirectory
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

$onlineSerials = @(& $adb devices | Select-String '^\S+\s+device$' | ForEach-Object { ($_ -split '\s+')[0] })
if ($LASTEXITCODE -ne 0) { throw "ADB could not list devices." }
if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
    if ($onlineSerials.Count -ne 1) {
        throw "Expected exactly one authorized device; found $($onlineSerials.Count). Pass -DeviceSerial to select one."
    }
    $DeviceSerial = $onlineSerials[0]
} elseif ($DeviceSerial -notin $onlineSerials) {
    throw "Device '$DeviceSerial' is not authorized and online."
}

function Invoke-Adb { & $adb -s $DeviceSerial @args }

function Copy-AppPrivateFileFromDevice {
    param([string]$DevicePath, [string]$HostPath)
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $adb
    $startInfo.Arguments = "-s $DeviceSerial exec-out run-as $appId cat $DevicePath"
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $output = [System.IO.File]::Create($HostPath)
    try {
        $process.StandardOutput.BaseStream.CopyTo($output)
    } finally {
        $output.Dispose()
    }
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        Remove-Item -LiteralPath $HostPath -Force -ErrorAction SilentlyContinue
        throw "Could not copy app-private report '$DevicePath' from the device."
    }
}

Push-Location -LiteralPath $repoRoot
try {
    if (-not $SkipBuild) {
        Write-Output "Building and installing the debug app and instrumentation APK on $DeviceSerial..."
        & $gradle :app:installDebug :app:installDebugAndroidTest "-Pandroid.injected.device.serial=$DeviceSerial"
        if ($LASTEXITCODE -ne 0) { throw "Gradle install failed with exit code $LASTEXITCODE." }
    } elseif (-not $SkipModelPush) {
        # The model staging helper needs the debug app installed; without a build,
        # only the APKs from a prior install are available.
        Write-Output "Skipping the build; assuming the debug app is already installed."
    }

    if (-not $SkipModelPush) {
        & (Join-Path $PSScriptRoot "push-parakeet-eou.ps1") -DeviceSerial $DeviceSerial
        if ($LASTEXITCODE -ne 0) { throw "Could not stage the pinned Parakeet-EOU model on the device." }
    }

    $extension = $input.Extension.ToLowerInvariant()
    $stagedName = "clip_001$extension"
    Invoke-Adb shell mkdir -p $deviceTempDirectory | Out-Null
    Invoke-Adb shell run-as $appId mkdir -p $remoteRunDirectory | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not create the harness stage directory on the device." }

    Write-Output "Staging $($input.Name) as $stagedName..."
    Invoke-Adb push $input.FullName "$deviceTempDirectory/$stagedName" | Out-Null
    Invoke-Adb shell run-as $appId cp "$deviceTempDirectory/$stagedName" "$remoteRunDirectory/$stagedName" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not copy the clip into app-private cache." }

    $instrumentationArguments = @(
        "-w",
        "-e", "class", $testClass,
        "-e", "sttHarnessRunId", $runId
    )
    if ($SmartTurn) { $instrumentationArguments += @("-e", "sttHarnessSmartTurn", "true") }

    Invoke-Adb logcat -c | Out-Null
    Write-Output "Running the STT pipeline harness (runId=$runId, smartTurn=$SmartTurn)..."
    $instrumentationOutput = @(& $adb -s $DeviceSerial shell am instrument @instrumentationArguments $instrumentationComponent 2>&1)
    $exitCode = $LASTEXITCODE
    $instrumentationText = $instrumentationOutput -join "`n"

    $logcatPath = Join-Path $OutputDirectory "harness-logcat.txt"
    @(& $adb -s $DeviceSerial logcat -d -s "SttHarness:I" "VoiceChat:V" "VoiceAgent:V") | Set-Content -LiteralPath $logcatPath
    Write-Output "---- harness stage log (SttHarness) ----"
    Select-String -LiteralPath $logcatPath -Pattern "SttHarness" | ForEach-Object { Write-Output $_.Line }

    foreach ($report in @("summary.csv", "stages.csv")) {
        try {
            Copy-AppPrivateFileFromDevice -DevicePath "$remoteRunDirectory/$report" -HostPath (Join-Path $OutputDirectory $report)
        } catch {
            Write-Warning "The harness did not produce $report. Check the instrumentation output."
        }
    }

    foreach ($report in @("summary.csv", "stages.csv")) {
        $hostPath = Join-Path $OutputDirectory $report
        if (Test-Path -LiteralPath $hostPath) {
            Write-Output "---- $report ----"
            Get-Content -LiteralPath $hostPath | ForEach-Object { Write-Output $_ }
        }
    }

    if ($exitCode -ne 0 -or $instrumentationText -match "INSTRUMENTATION_CODE:\s*-1") {
        throw "The STT pipeline harness failed (exit $exitCode). Available reports are in '$OutputDirectory'."
    }
    Write-Output "Harness reports written to $OutputDirectory"
} finally {
    Invoke-Adb shell run-as $appId rm -r $remoteRunDirectory 2>$null | Out-Null
    Invoke-Adb shell rm -r $deviceTempDirectory 2>$null | Out-Null
    Pop-Location
}
