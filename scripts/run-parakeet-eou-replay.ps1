param(
    [string]$InputPath = "test_data",
    [string]$OutputCsv,
    [string]$DeviceSerial,
    [switch]$ExportDraftLabels,
    [string]$DraftLabelsPath
)

# Runs the on-device Parakeet-EOU streaming replay: stages the WAV/M4A clips
# into the debug app's private cache, runs the instrumented measurement, and
# pulls the content-free timing CSV (and, only when asked, a Git-ignored
# draft-label sidecar). See docs/parakeet-eou-streaming.md.
$ErrorActionPreference = "Continue"

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$inputCandidate = if ([System.IO.Path]::IsPathRooted($InputPath)) { $InputPath } else { Join-Path $repoRoot $InputPath }
$inputResolved = (Resolve-Path -LiteralPath $inputCandidate).Path
$runId = [guid]::NewGuid().ToString("N")

if ([string]::IsNullOrWhiteSpace($OutputCsv)) {
    $OutputCsv = Join-Path $env:TEMP "voicechat-parakeet-eou-replay-$runId.csv"
} elseif (-not [System.IO.Path]::IsPathRooted($OutputCsv)) {
    $OutputCsv = Join-Path (Get-Location).Path $OutputCsv
}
$OutputCsv = [System.IO.Path]::GetFullPath($OutputCsv)

$draftLabels = $null
if ($ExportDraftLabels) {
    if ([string]::IsNullOrWhiteSpace($DraftLabelsPath)) {
        $DraftLabelsPath = Join-Path $repoRoot "test_data/parakeet-eou-draft-labels-$runId.csv"
    } elseif (-not [System.IO.Path]::IsPathRooted($DraftLabelsPath)) {
        $DraftLabelsPath = Join-Path $repoRoot $DraftLabelsPath
    }
    $draftLabels = [System.IO.Path]::GetFullPath($DraftLabelsPath)
}

$appId = "com.voicechat.agent"
$instrumentationComponent = "$appId.test/androidx.test.runner.AndroidJUnitRunner"
$deviceTempRunDirectory = "/data/local/tmp/voicechat-stt-replay-$runId"
$remoteRunDirectory = "/data/user/0/$appId/cache/voicechat-stt-replay/$runId"
$adb = Join-Path (Join-Path $env:LOCALAPPDATA "Android\Sdk") "platform-tools\adb.exe"
$gradle = Join-Path $repoRoot "gradlew.bat"

if (-not (Test-Path -LiteralPath $adb)) { throw "Android SDK ADB was not found at '$adb'." }
if (-not (Test-Path -LiteralPath $gradle)) { throw "Gradle wrapper was not found at '$gradle'." }
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk"

$onlineSerials = @(& $adb devices | Select-String '^\S+\s+device$' | ForEach-Object { ($_ -split '\s+')[0] })
if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
    if ($onlineSerials.Count -ne 1) { throw "Expected exactly one authorized device; found $($onlineSerials.Count)." }
    $DeviceSerial = $onlineSerials[0]
} elseif ($DeviceSerial -notin $onlineSerials) {
    throw "Device '$DeviceSerial' is not authorized and online."
}

$sourceFiles = @(Get-ChildItem -LiteralPath $inputResolved -File |
    Where-Object { $_.Extension -in @(".wav", ".WAV", ".m4a", ".M4A") } | Sort-Object Name)
if ($sourceFiles.Count -eq 0) { throw "No WAV or M4A audio files were found at '$inputResolved'." }

$fileRows = @()
for ($index = 0; $index -lt $sourceFiles.Count; $index++) {
    $fileRows += [pscustomobject]@{ FileId = "audio_{0:D3}" -f ($index + 1); File = $sourceFiles[$index] }
}

Push-Location -LiteralPath $repoRoot
$remoteDirectoryCreated = $false
try {
    Write-Output "Installing the debug app and instrumentation APK on $DeviceSerial..."
    & $gradle :app:installDebug :app:installDebugAndroidTest "-Pandroid.injected.device.serial=$DeviceSerial"
    if ($LASTEXITCODE -ne 0) { throw "Gradle install failed with exit code $LASTEXITCODE." }

    & (Join-Path $PSScriptRoot "push-parakeet-eou.ps1") -DeviceSerial $DeviceSerial
    if ($LASTEXITCODE -ne 0) { throw "Could not stage the pinned Parakeet-EOU model on the device." }

    & $adb -s $DeviceSerial shell mkdir -p $deviceTempRunDirectory
    $remoteDirectoryCreated = $true
    & $adb -s $DeviceSerial shell run-as $appId mkdir -p $remoteRunDirectory

    foreach ($row in $fileRows) {
        $fileName = "$($row.FileId)$($row.File.Extension.ToLowerInvariant())"
        & $adb -s $DeviceSerial push $row.File.FullName "$deviceTempRunDirectory/$fileName" | Out-Null
        & $adb -s $DeviceSerial shell run-as $appId cp "$deviceTempRunDirectory/$fileName" "$remoteRunDirectory/$fileName"
    }
    Write-Output "Staged $($fileRows.Count) audio files into app-private cache."

    $arguments = @("-w", "-e", "class", "com.voicechat.agent.stt.parakeet.ParakeetEouReplayInstrumentedTest",
        "-e", "parakeetEouRunId", $runId)
    if ($ExportDraftLabels) { $arguments += @("-e", "parakeetEouExportDraftLabels", "true") }

    Write-Output "Running on-device Parakeet-EOU streaming replay..."
    $output = @(& $adb -s $DeviceSerial shell am instrument @arguments $instrumentationComponent 2>&1)
    $output | ForEach-Object { Write-Output $_ }
    $instrumentationText = $output -join "`n"
    if ($LASTEXITCODE -ne 0 -or $instrumentationText -match "INSTRUMENTATION_CODE:\s*-1") {
        throw "The instrumentation run failed; see the output above."
    }

    $deviceCsv = Join-Path $env:TEMP "voicechat-parakeet-eou-$runId-device.csv"
    & $adb -s $DeviceSerial exec-out run-as $appId cat "$remoteRunDirectory/parakeet-eou-results.csv" > $deviceCsv
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $deviceCsv)) {
        throw "The instrumented test did not produce its content-free timing CSV."
    }
    $rows = foreach ($deviceRow in Import-Csv -LiteralPath $deviceCsv) {
        $source = $fileRows | Where-Object { $_.FileId -eq $deviceRow.file_id }
        [pscustomobject][ordered]@{
            file_id = $deviceRow.file_id
            source_file = if ($source) { $source.File.Name } else { "unknown" }
            engine_id = $deviceRow.engine_id
            model_id = $deviceRow.model_id
            input_duration_ms = $deviceRow.input_duration_ms
            model_open_ms = $deviceRow.model_open_ms
            audio_decode_ms = $deviceRow.audio_decode_ms
            chunk_decode_ms = $deviceRow.chunk_decode_ms
            chunks = $deviceRow.chunks
            chunks_with_text = $deviceRow.chunks_with_text
            eou_commits = $deviceRow.eou_commits
            first_partial_chunk = $deviceRow.first_partial_chunk
            real_time_factor = $deviceRow.real_time_factor
            outcome = $deviceRow.outcome
            error_code = $deviceRow.error_code
        }
    }
    $rows | Export-Csv -LiteralPath $OutputCsv -NoTypeInformation -Encoding UTF8
    Write-Output "Content-free Parakeet-EOU replay rows: $OutputCsv"

    if ($ExportDraftLabels) {
        $deviceDraft = Join-Path $env:TEMP "voicechat-parakeet-eou-$runId-drafts.csv"
        & $adb -s $DeviceSerial exec-out run-as $appId cat "$remoteRunDirectory/parakeet-eou-draft-labels.csv" > $deviceDraft
        if (Test-Path -LiteralPath $deviceDraft) {
            Copy-Item -LiteralPath $deviceDraft -Destination $draftLabels -Force
            Write-Output "On-device, unverified draft transcripts: $draftLabels (Git-ignored)"
        }
    }
} finally {
    if ($remoteDirectoryCreated) {
        & $adb -s $DeviceSerial shell run-as $appId rm -r $remoteRunDirectory | Out-Null
        & $adb -s $DeviceSerial shell rm -r $deviceTempRunDirectory | Out-Null
    }
    Pop-Location
}
