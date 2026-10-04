param(
    [Parameter(Mandatory = $true)][string]$InputPath,
    [string]$DeviceSerial,
    [string]$CredentialPath = "secrets/opencode-go.key",
    [string]$Package = "com.voicechat.agent",
    [int]$TapX = 0,
    [int]$TapY = 0,
    [int]$ListenSeconds = 60,
    [switch]$SkipBuild,
    [string]$ScreenshotDirectory
)

# Drives the whole Parakeet-EOU voice loop on device from a WAV file, with the UI
# running and the talk control tapped, so the pipeline can be validated without a
# live microphone: capture (staged WAV) -> VAD onset/pause -> Parakeet-EOU STT ->
# provider LLM -> on-device TTS. It stages a debug-only file audio source
# (app/src/debug/.../DebugFileAudioInput.kt), pushes an optional developer
# credential, taps the talk orb, then captures the logcat to a file and periodic
# screenshots. Requires the debug build; release ignores the staged WAV.
$ErrorActionPreference = "Continue"

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$inputResolved = (Resolve-Path -LiteralPath (Join-Path $repoRoot $InputPath)).Path
$adb = Join-Path (Join-Path $env:LOCALAPPDATA "Android\Sdk") "platform-tools\adb.exe"
$gradle = Join-Path $repoRoot "gradlew.bat"
if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found at '$adb'." }
if (-not (Test-Path -LiteralPath $inputResolved)) { throw "WAV '$inputResolved' not found." }
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk"
if (-not $ScreenshotDirectory) { $ScreenshotDirectory = Join-Path $repoRoot "build/screens" }

$onlineSerials = @(& $adb devices | Select-String '^\S+\s+device$' | ForEach-Object { ($_ -split '\s+')[0] })
if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
    if ($onlineSerials.Count -ne 1) { throw "Expected exactly one authorized device; found $($onlineSerials.Count)." }
    $DeviceSerial = $onlineSerials[0]
} elseif ($DeviceSerial -notin $onlineSerials) {
    throw "Device '$DeviceSerial' is not authorized and online."
}

function Invoke-Adb { & $adb -s $DeviceSerial @args }

if (-not $SkipBuild) {
    Push-Location $repoRoot
    try {
        & $gradle :app:installDebug "-Pandroid.injected.device.serial=$DeviceSerial"
        if ($LASTEXITCODE -ne 0) { throw "Gradle install failed with exit code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }
}

Invoke-Adb shell run-as $Package mkdir -p files/debug-audio | Out-Null
Invoke-Adb push $inputResolved /data/local/tmp/voicechat-input.wav 2>&1 | Out-Null
Invoke-Adb shell "run-as $Package cp /data/local/tmp/voicechat-input.wav files/debug-audio/input.wav"
Invoke-Adb shell rm /data/local/tmp/voicechat-input.wav | Out-Null

$credentialResolved = Join-Path $repoRoot $CredentialPath
if (Test-Path -LiteralPath $credentialResolved) {
    Invoke-Adb shell run-as $Package mkdir -p files/debug-credentials | Out-Null
    Invoke-Adb push $credentialResolved /data/local/tmp/voicechat-credential.key 2>&1 | Out-Null
    Invoke-Adb shell "run-as $Package cp /data/local/tmp/voicechat-credential.key files/debug-credentials/opencode-go.key"
    Invoke-Adb shell rm /data/local/tmp/voicechat-credential.key | Out-Null
    Write-Output "Staged a developer credential for the next launch (value never printed)."
}

Invoke-Adb shell pm grant $Package android.permission.RECORD_AUDIO 2>&1 | Out-Null
Invoke-Adb shell am force-stop $Package | Out-Null
Invoke-Adb logcat -c | Out-Null
$logFile = Join-Path $ScreenshotDirectory "voice-loop-logcat.txt"
New-Item -ItemType Directory -Force -Path $ScreenshotDirectory | Out-Null
$logJob = Start-Job -ScriptBlock {
    param($adbPath, $serial, $outFile)
    & $adbPath -s $serial logcat | Out-File -LiteralPath $outFile -Encoding utf8
} -ArgumentList $adb, $DeviceSerial, $logFile

Invoke-Adb shell am start -n "$Package/.MainActivity" | Out-Null
Start-Sleep -Seconds 5

if ($TapX -eq 0 -or $TapY -eq 0) {
    $sizeLine = (Invoke-Adb shell wm size | Select-String "Override size|Physical size" | Select-Object -First 1).ToString()
    $dims = [regex]::Matches($sizeLine, "\d+") | ForEach-Object { [int]$_.Value }
    $width = $dims[-2]
    $height = $dims[-1]
    # The home talk orb is horizontally centered, above the "New chat" button.
    $TapX = [int]($width * 0.5)
    $TapY = [int]($height * 0.76)
}
Write-Output "Tapping the talk control at ($TapX,$TapY)..."
Invoke-Adb shell input tap $TapX $TapY | Out-Null

for ($elapsed = 0; $elapsed -lt $ListenSeconds; $elapsed += 15) {
    Start-Sleep -Seconds ([Math]::Min(15, $ListenSeconds - $elapsed))
    Invoke-Adb shell screencap -p /sdcard/voicechat-loop.png | Out-Null
    Invoke-Adb pull /sdcard/voicechat-loop.png (Join-Path $ScreenshotDirectory ("voice-loop-{0:D3}s.png" -f ($elapsed + 15))) | Out-Null
    Invoke-Adb shell rm /sdcard/voicechat-loop.png | Out-Null
}

Stop-Job $logJob -ErrorAction SilentlyContinue
Remove-Job $logJob -Force -ErrorAction SilentlyContinue
$voiceEvents = Get-Content -LiteralPath $logFile -ErrorAction SilentlyContinue |
    Select-String -Pattern "VoiceChat" |
    Select-String -Pattern "debug-audio|vad:|voice:|stt: parakeet|orchestration:|llm:|tts:"
Write-Output "---- voice loop log (VoiceChat tag) ----"
$voiceEvents | ForEach-Object { Write-Output $_ }
Write-Output "Log: $logFile"
Write-Output "Screenshots: $ScreenshotDirectory"
