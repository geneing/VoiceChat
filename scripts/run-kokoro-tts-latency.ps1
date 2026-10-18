param(
    # Attempts per text; the record shows the median.
    [int]$Repeats = 3,
    [string]$DeviceSerial,
    # Where the copied reports land on the host. Defaults to build/kokoro-tts-latency/<runId>.
    [string]$OutputDirectory,
    [switch]$SkipBuild
)

# Measures the M28 distilled-Kokoro TTS stage ON THE DEVICE: text start to first
# sound.
#
# Two measurements run in one instrumentation pass, because "first sound" has two
# honest halves and only a device can produce either:
#
#   * synthesis.md - KokoroTtsStageHarness drives the real phonemizer and the real
#     ONNX graph and records text start -> phonemes -> first PCM -> all PCM. This
#     is everything that must happen before the audio sink gets its first buffer,
#     so it is what a synthesis or phonemizer regression moves.
#   * audible-*.md - the M25 VoiceTtsHarness drives the same texts through the
#     real engine and AudioTrack and records `tts_first_audible`, the moment audio
#     actually became audible. The gap between the two is the audio device's own
#     start cost.
#
# The voice model must already be installed in app-private storage
# (scripts/fetch-models.sh then scripts/push-models.sh kokoro-af-heart); the test
# skips rather than downloading or faking. Nothing here touches the microphone,
# the recognizer, or the LLM.
#
# Usage (from the repository root, in PowerShell):
#   .\scripts\run-kokoro-tts-latency.ps1
#   .\scripts\run-kokoro-tts-latency.ps1 -Repeats 5 -DeviceSerial <serial>
$ErrorActionPreference = "Continue"

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$adb = Join-Path (Join-Path $env:LOCALAPPDATA "Android\Sdk") "platform-tools\adb.exe"
$gradle = Join-Path $repoRoot "gradlew.bat"
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$appId = "com.voicechat.agent"
$instrumentationComponent = "$appId.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = "com.voicechat.agent.eval.tts.KokoroTtsHarnessInstrumentedTest"
$runId = [guid]::NewGuid().ToString("N")
$remoteRunDirectory = "/data/user/0/$appId/cache/kokoro-tts/$runId"
$reports = @("synthesis-summary.md", "synthesis-record.md", "audible-summary.md", "audible-record.md")

if (-not (Test-Path -LiteralPath $adb)) { throw "Android SDK ADB was not found at '$adb'." }
if (-not (Test-Path -LiteralPath $gradle)) { throw "Gradle wrapper was not found at '$gradle'." }

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

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repoRoot "build/kokoro-tts-latency/$runId"
} elseif (-not [System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $repoRoot $OutputDirectory
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

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
    } else {
        Write-Output "Skipping the build; assuming the debug app is already installed."
    }

    Invoke-Adb shell run-as $appId mkdir -p $remoteRunDirectory | Out-Null

    $instrumentationArguments = @(
        "-w",
        "-e", "class", $testClass,
        "-e", "kokoroHarnessRunId", $runId,
        "-e", "kokoroHarnessRepeats", "$Repeats"
    )

    Invoke-Adb logcat -c | Out-Null
    Write-Output "Running the on-device Kokoro latency harness (runId=$runId, repeats=$Repeats)..."
    $instrumentationOutput = @(& $adb -s $DeviceSerial shell am instrument @instrumentationArguments $instrumentationComponent 2>&1)
    $exitCode = $LASTEXITCODE
    $instrumentationText = $instrumentationOutput -join "`n"

    $logcatPath = Join-Path $OutputDirectory "harness-logcat.txt"
    @(& $adb -s $DeviceSerial logcat -d -s "KokoroTtsHarness:I" "TtsHarness:I" "VoiceChat:V" "VoiceAgent:V") |
        Set-Content -LiteralPath $logcatPath
    Write-Output "---- harness summary (KokoroTtsHarness) ----"
    Select-String -LiteralPath $logcatPath -Pattern "KokoroTtsHarness" -SimpleMatch | ForEach-Object { Write-Output $_.Line }

    foreach ($report in $reports) {
        try {
            Copy-AppPrivateFileFromDevice -DevicePath "$remoteRunDirectory/$report" -HostPath (Join-Path $OutputDirectory $report)
        } catch {
            Write-Warning "The harness did not produce $report. Check the instrumentation output."
        }
    }

    foreach ($report in $reports) {
        $hostPath = Join-Path $OutputDirectory $report
        if (Test-Path -LiteralPath $hostPath) {
            Write-Output "---- $report ----"
            Get-Content -LiteralPath $hostPath | ForEach-Object { Write-Output $_ }
        }
    }

    if ($exitCode -ne 0 -or $instrumentationText -match "INSTRUMENTATION_CODE:\s*-1") {
        throw "The on-device Kokoro latency harness failed (exit $exitCode). Available reports are in '$OutputDirectory'."
    }
    Write-Output "Kokoro latency reports written to $OutputDirectory"
} finally {
    Invoke-Adb shell run-as $appId rm -r $remoteRunDirectory 2>$null | Out-Null
    Pop-Location
}
