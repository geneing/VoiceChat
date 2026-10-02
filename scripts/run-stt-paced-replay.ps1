param(
    [string]$InputPath = "test_data",
    [ValidateSet("MlKit", "Moonshine", "Both")]
    [string]$Engine = "MlKit",
    [ValidateSet("Advanced", "Basic", "Both")]
    [string]$Mode = "Advanced",
    [string]$OutputCsv,
    [string]$MoonshineOutputCsv,
    [string]$DeviceSerial,
    [switch]$ExportDraftLabels,
    [string]$DraftLabelsPath,
    [switch]$ExportMoonshineDraftLabels,
    [string]$MoonshineDraftLabelsPath
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$inputCandidate = if ([System.IO.Path]::IsPathRooted($InputPath)) { $InputPath } else { Join-Path $repoRoot $InputPath }
$inputResolved = (Resolve-Path -LiteralPath $inputCandidate).Path
$runId = [guid]::NewGuid().ToString("N")

function Resolve-DraftLabelsOutput {
    param(
        [switch]$Enabled,
        [string]$RequestedPath,
        [string]$DefaultFileName,
        [string]$OptionName
    )

    if (-not $Enabled) {
        if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
            throw "Pass -$OptionName to write a local draft transcript sidecar."
        }
        return $null
    }

    if ([string]::IsNullOrWhiteSpace($RequestedPath)) {
        $RequestedPath = Join-Path $repoRoot $DefaultFileName
    } elseif (-not [System.IO.Path]::IsPathRooted($RequestedPath)) {
        $RequestedPath = Join-Path $repoRoot $RequestedPath
    }
    $fullPath = [System.IO.Path]::GetFullPath($RequestedPath)
    if (Test-Path -LiteralPath $fullPath) {
        throw "Draft-label output already exists; choose a new path to preserve existing files."
    }

    $repoPrefix = $repoRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith($repoPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Draft-label output must be under this repository and ignored by Git."
    }
    $relativePath = $fullPath.Substring($repoPrefix.Length).Replace("\", "/")
    & git -c "safe.directory=$repoRoot" -C $repoRoot check-ignore --quiet -- $relativePath
    if ($LASTEXITCODE -ne 0) {
        throw "Draft-label path '$relativePath' is not ignored by Git; choose an ignored local path."
    }

    return [pscustomobject]@{ Path = $fullPath; RelativePath = $relativePath }
}

if ($Engine -eq "Moonshine" -and $ExportDraftLabels) {
    throw "-ExportDraftLabels applies to ML Kit; use -ExportMoonshineDraftLabels for Moonshine."
}
if ($Engine -eq "MlKit" -and $ExportMoonshineDraftLabels) {
    throw "-ExportMoonshineDraftLabels requires -Engine Moonshine or Both."
}
$mlDraftLabels = Resolve-DraftLabelsOutput `
    -Enabled:$ExportDraftLabels `
    -RequestedPath $DraftLabelsPath `
    -DefaultFileName "test_data/stt-draft-labels-$runId.csv" `
    -OptionName "ExportDraftLabels"
$moonshineDraftLabels = Resolve-DraftLabelsOutput `
    -Enabled:$ExportMoonshineDraftLabels `
    -RequestedPath $MoonshineDraftLabelsPath `
    -DefaultFileName "test_data/stt-draft-labels-moonshine-$runId.csv" `
    -OptionName "ExportMoonshineDraftLabels"
if ($mlDraftLabels -and $moonshineDraftLabels -and
    $mlDraftLabels.Path.Equals($moonshineDraftLabels.Path, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "ML Kit and Moonshine draft labels need separate local output paths."
}
foreach ($draftOutput in @($mlDraftLabels, $moonshineDraftLabels) | Where-Object { $null -ne $_ }) {
    $draftOutputDirectory = Split-Path -Parent $draftOutput.Path
    if (-not (Test-Path -LiteralPath $draftOutputDirectory)) {
        New-Item -ItemType Directory -Path $draftOutputDirectory -Force | Out-Null
    }
}

if ([string]::IsNullOrWhiteSpace($OutputCsv)) {
    $OutputCsv = Join-Path $env:TEMP "voicechat-stt-replay-$runId.csv"
} elseif (-not [System.IO.Path]::IsPathRooted($OutputCsv)) {
    $OutputCsv = Join-Path (Get-Location).Path $OutputCsv
}
$OutputCsv = [System.IO.Path]::GetFullPath($OutputCsv)
if ([string]::IsNullOrWhiteSpace($MoonshineOutputCsv)) {
    $MoonshineOutputCsv = Join-Path $env:TEMP "voicechat-moonshine-stt-replay-$runId.csv"
} elseif (-not [System.IO.Path]::IsPathRooted($MoonshineOutputCsv)) {
    $MoonshineOutputCsv = Join-Path (Get-Location).Path $MoonshineOutputCsv
}
$MoonshineOutputCsv = [System.IO.Path]::GetFullPath($MoonshineOutputCsv)
if ($Engine -eq "Both" -and $OutputCsv.Equals($MoonshineOutputCsv, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "ML Kit and Moonshine timing reports need separate output paths."
}

$outputDirectories = @()
if ($Engine -in @("MlKit", "Both")) { $outputDirectories += Split-Path -Parent $OutputCsv }
if ($Engine -in @("Moonshine", "Both")) { $outputDirectories += Split-Path -Parent $MoonshineOutputCsv }
foreach ($outputDirectory in $outputDirectories | Select-Object -Unique) {
    if (-not (Test-Path -LiteralPath $outputDirectory)) {
        New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    }
}

$sourceFiles = if (Test-Path -LiteralPath $inputResolved -PathType Container) {
    @(Get-ChildItem -LiteralPath $inputResolved -File |
        Where-Object { $_.Extension -in @(".wav", ".WAV", ".m4a", ".M4A") } |
        Sort-Object Name)
} else {
    @((Get-Item -LiteralPath $inputResolved) | Where-Object { $_.Extension -in @(".wav", ".WAV", ".m4a", ".M4A") })
}
if ($sourceFiles.Count -eq 0) {
    throw "No WAV or M4A audio files were found at '$inputResolved'."
}

$fileById = @{}
$fileRows = @()
for ($index = 0; $index -lt $sourceFiles.Count; $index++) {
    $fileId = "audio_{0:D3}" -f ($index + 1)
    $file = $sourceFiles[$index]
    $fileById[$fileId] = $file
    $fileRows += [pscustomobject]@{ FileId = $fileId; File = $file }
}
$wavRows = @($fileRows | Where-Object { $_.File.Extension -in @(".wav", ".WAV") })
$m4aRows = @($fileRows | Where-Object { $_.File.Extension -in @(".m4a", ".M4A") })
$replayRows = @($fileRows | Where-Object { $_.File.Extension -in @(".wav", ".WAV", ".m4a", ".M4A") })

# The app package name is pinned in app/build.gradle.kts. Audio is staged into
# an isolated temporary directory, then copied into app-private cache with
# run-as so Android's scoped-storage view does not hide shell-owned files.
$appId = "com.voicechat.agent"
$instrumentationComponent = "$appId.test/androidx.test.runner.AndroidJUnitRunner"
$deviceTempRunDirectory = "/data/local/tmp/voicechat-stt-replay-$runId"
$remoteRunDirectory = "/data/user/0/$appId/cache/voicechat-stt-replay/$runId"
$adb = Join-Path (Join-Path $env:LOCALAPPDATA "Android\Sdk") "platform-tools\adb.exe"
$gradle = Join-Path $repoRoot "gradlew.bat"

if (-not (Test-Path -LiteralPath $adb)) {
    throw "Android SDK ADB was not found at '$adb'. Set LOCALAPPDATA to the SDK-owning Windows profile."
}
if (-not (Test-Path -LiteralPath $gradle)) {
    throw "Gradle wrapper was not found at '$gradle'."
}
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk"

function Copy-AppPrivateFileFromDevice {
    param(
        [string]$DevicePath,
        [string]$HostPath
    )

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

$onlineSerials = @(& $adb devices | Select-String '^\S+\s+device$' | ForEach-Object { ($_ -split '\s+')[0] })
if ($LASTEXITCODE -ne 0) {
    throw "ADB could not list devices."
}
if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
    if ($onlineSerials.Count -ne 1) {
        throw "Expected exactly one authorized device; found $($onlineSerials.Count). Pass -DeviceSerial to select one."
    }
    $DeviceSerial = $onlineSerials[0]
} elseif ($DeviceSerial -notin $onlineSerials) {
    throw "Device '$DeviceSerial' is not authorized and online."
}

$targets = @()
if ($Engine -in @("MlKit", "Both")) {
    $targets += [pscustomobject]@{
        Name = "ML Kit"
        ClassName = "com.voicechat.agent.stt.MlKitSttPacedReplayInstrumentedTest"
        ResultsFile = "results.csv"
        DraftFile = "draft-labels.csv"
        ExportDraftLabels = [bool]$ExportDraftLabels
        DraftOutput = $mlDraftLabels
        OutputCsv = $OutputCsv
        IsMlKit = $true
    }
}
if ($Engine -in @("Moonshine", "Both")) {
    $targets += [pscustomobject]@{
        Name = "Moonshine Tiny/Small"
        ClassName = "com.voicechat.agent.stt.MoonshineSttPacedReplayInstrumentedTest"
        ResultsFile = "moonshine-results.csv"
        DraftFile = "moonshine-draft-labels.csv"
        ExportDraftLabels = [bool]$ExportMoonshineDraftLabels
        DraftOutput = $moonshineDraftLabels
        OutputCsv = $MoonshineOutputCsv
        IsMlKit = $false
    }
}
$failedTargets = @()
$deviceTempFiles = @()
$remoteDirectoryCreated = $false
Push-Location -LiteralPath $repoRoot

try {
    if ($replayRows.Count -gt 0) {
        Write-Output "Installing the debug app and instrumentation APK on $DeviceSerial..."
        & $gradle :app:installDebug :app:installDebugAndroidTest "-Pandroid.injected.device.serial=$DeviceSerial"
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle install failed with exit code $LASTEXITCODE."
        }

        & $adb -s $DeviceSerial shell mkdir -p $deviceTempRunDirectory
        if ($LASTEXITCODE -ne 0) {
            throw "Could not create the temporary audio staging directory on the device."
        }
        $remoteDirectoryCreated = $true
        & $adb -s $DeviceSerial shell run-as $appId mkdir -p $remoteRunDirectory
        if ($LASTEXITCODE -ne 0) {
            throw "Could not create the run-specific app-private cache directory on the device."
        }

        foreach ($row in $replayRows) {
            $extension = $row.File.Extension.ToLowerInvariant()
            $fileName = "$($row.FileId)$extension"
            $temporaryRemoteFile = "$deviceTempRunDirectory/$fileName"
            $remoteFile = "$remoteRunDirectory/$fileName"
            Write-Output "Copying $($row.File.Name) to the run-specific device cache..."
            & $adb -s $DeviceSerial push $row.File.FullName $temporaryRemoteFile
            if ($LASTEXITCODE -ne 0) {
                throw "ADB could not copy audio file '$($row.File.Name)' to the device."
            }
            & $adb -s $DeviceSerial shell run-as $appId cp $temporaryRemoteFile $remoteFile
            if ($LASTEXITCODE -ne 0) {
                throw "The app could not copy staged audio file '$($row.File.Name)' into its private cache."
            }
        }

        $expectedDeviceNames = @(
            $replayRows | ForEach-Object { "$($_.FileId)$($_.File.Extension.ToLowerInvariant())" }
        )
        $stagedDeviceNames = @(
            & $adb -s $DeviceSerial shell run-as $appId ls -1 $remoteRunDirectory 2>$null |
                ForEach-Object { $_.Trim() } |
                Where-Object { $_ -match '^audio_\d{3}\.(wav|m4a)$' }
        )
        if ($LASTEXITCODE -ne 0) {
            throw "ADB could not verify staged audio in the run-specific cache directory."
        }
        $missingDeviceNames = @($expectedDeviceNames | Where-Object { $_ -notin $stagedDeviceNames })
        if ($missingDeviceNames.Count -gt 0) {
            throw "Staging verification failed in '$remoteRunDirectory'; $($missingDeviceNames.Count) of $($expectedDeviceNames.Count) expected audio files are missing."
        }
        Write-Output "Verified app-private cache path '$remoteRunDirectory': $($expectedDeviceNames.Count) sanitized audio files staged."

        foreach ($target in $targets) {
            Write-Output "Running real-time paced $($target.Name) STT replay..."
            $instrumentationArguments = @(
                "-w",
                "-e", "class", $target.ClassName,
                "-e", "sttReplayRunId", $runId
            )
            if ($target.IsMlKit) {
                $draftExportArgument = if ($target.ExportDraftLabels) { "true" } else { "false" }
                $instrumentationArguments += @("-e", "sttReplayMode", $Mode.ToUpperInvariant())
                $instrumentationArguments += @("-e", "sttReplayExportDraftLabels", $draftExportArgument)
            } elseif ($target.ExportDraftLabels) {
                # Moonshine stays transcript-free unless its dedicated opt-in switch was supplied.
                $instrumentationArguments += @("-e", "sttReplayExportDraftLabels", "true")
            }

            $instrumentationOutput = @(
                & $adb -s $DeviceSerial shell am instrument @instrumentationArguments $instrumentationComponent 2>&1
            )
            $targetExitCode = $LASTEXITCODE
            $instrumentationOutput | ForEach-Object { Write-Output $_ }
            $instrumentationText = $instrumentationOutput -join "`n"
            if ($targetExitCode -ne 0 -or $instrumentationText -match "INSTRUMENTATION_CODE:\s*-1") {
                $failedTargets += "$($target.Name) (exit $targetExitCode)"
            }

            $deviceCsvPath = Join-Path $env:TEMP "voicechat-stt-replay-$runId-$($target.IsMlKit)-device.csv"
            $deviceTempFiles += $deviceCsvPath
            $remoteResults = "$remoteRunDirectory/$($target.ResultsFile)"
            try {
                Copy-AppPrivateFileFromDevice -DevicePath $remoteResults -HostPath $deviceCsvPath
            } catch {
                Write-Warning "$($target.Name) did not produce its content-free timing CSV. Check the instrumentation output."
            }
            if (Test-Path -LiteralPath $deviceCsvPath) {
                $targetRows = foreach ($deviceRow in Import-Csv -LiteralPath $deviceCsvPath) {
                    $source = $fileById[$deviceRow.file_id]
                    [pscustomobject][ordered]@{
                        file_id = $deviceRow.file_id
                        source_file = if ($source) { $source.Name } else { "unknown" }
                        engine_id = $deviceRow.engine_id
                        model_id = $deviceRow.model_id
                        mode = $deviceRow.mode
                        input_duration_ms = $deviceRow.input_duration_ms
                        model_open_ms = $deviceRow.model_open_ms
                        input_start_ms = $deviceRow.input_start_ms
                        first_partial_ms = $deviceRow.first_partial_ms
                        input_end_ms = $deviceRow.input_end_ms
                        final_ms = $deviceRow.final_ms
                        failure_ms = $deviceRow.failure_ms
                        input_start_to_first_partial_ms = $deviceRow.input_start_to_first_partial_ms
                        input_end_to_final_ms = $deviceRow.input_end_to_final_ms
                        wall_time_ms = $deviceRow.wall_time_ms
                        real_time_factor = $deviceRow.real_time_factor
                        outcome = $deviceRow.outcome
                        error_code = $deviceRow.error_code
                    }
                }
                if ($null -ne $targetRows) {
                    $targetRows | Export-Csv -LiteralPath $target.OutputCsv -NoTypeInformation -Encoding UTF8
                }
            } else {
                Write-Warning "$($target.Name) did not produce its content-free timing CSV. Check the test output."
            }

            if ($target.ExportDraftLabels) {
                $draftDeviceCsvPath = Join-Path $env:TEMP "voicechat-stt-replay-$runId-$($target.IsMlKit)-drafts.csv"
                $deviceTempFiles += $draftDeviceCsvPath
                $remoteDraftLabels = "$remoteRunDirectory/$($target.DraftFile)"
                try {
                    Copy-AppPrivateFileFromDevice -DevicePath $remoteDraftLabels -HostPath $draftDeviceCsvPath
                } catch {
                    Write-Warning "$($target.Name) draft-label export was enabled, but its sidecar was not produced."
                }
                if (Test-Path -LiteralPath $draftDeviceCsvPath) {
                    $draftRows = foreach ($draftRow in Import-Csv -LiteralPath $draftDeviceCsvPath) {
                        $source = $fileById[$draftRow.file_id]
                        [pscustomobject][ordered]@{
                            file_id = $draftRow.file_id
                            source_file = if ($source) { $source.Name } else { "unknown" }
                            engine_id = $draftRow.engine_id
                            model_id = $draftRow.model_id
                            mode = $draftRow.mode
                            draft_transcript = $draftRow.draft_transcript
                            label_origin = $draftRow.label_origin
                            verification_status = $draftRow.verification_status
                            outcome = $draftRow.outcome
                            error_code = $draftRow.error_code
                        }
                    }
                    $draftRows | Export-Csv -LiteralPath $target.DraftOutput.Path -NoTypeInformation -Encoding UTF8
                    & git -c "safe.directory=$repoRoot" -C $repoRoot check-ignore --quiet -- $target.DraftOutput.RelativePath
                    if ($LASTEXITCODE -ne 0) {
                        throw "Generated draft labels were not ignored by Git at '$($target.DraftOutput.RelativePath)'."
                    }
                } else {
                    Write-Warning "$($target.Name) draft-label export was enabled, but its sidecar was not produced."
                }
            }
        }
    }
} finally {
    if ($remoteDirectoryCreated) {
        & $adb -s $DeviceSerial shell run-as $appId rm -r $remoteRunDirectory | Out-Null
        & $adb -s $DeviceSerial shell rm -r $deviceTempRunDirectory | Out-Null
    }
    foreach ($temporaryFile in $deviceTempFiles) {
        Remove-Item -LiteralPath $temporaryFile -Force -ErrorAction SilentlyContinue
    }
    Pop-Location
}

if ($Engine -in @("MlKit", "Both") -and (Test-Path -LiteralPath $OutputCsv)) {
    Write-Output "Content-free ML Kit replay rows: $OutputCsv"
}
if ($Engine -in @("Moonshine", "Both") -and (Test-Path -LiteralPath $MoonshineOutputCsv)) {
    Write-Output "Content-free Moonshine Tiny/Small replay rows: $MoonshineOutputCsv"
}
Write-Output "WAV files: $($wavRows.Count); M4A files: $($m4aRows.Count); engine: $Engine; ML Kit mode: $Mode"
if ($ExportDraftLabels -and $mlDraftLabels -and (Test-Path -LiteralPath $mlDraftLabels.Path)) {
    Write-Output "ML Kit machine-generated, unverified draft labels: $($mlDraftLabels.Path) (Git-ignored)"
}
if ($ExportMoonshineDraftLabels -and $moonshineDraftLabels -and (Test-Path -LiteralPath $moonshineDraftLabels.Path)) {
    Write-Output "Moonshine machine-generated, unverified draft labels: $($moonshineDraftLabels.Path) (Git-ignored)"
}
if ($failedTargets.Count -gt 0) {
    throw "One or more on-device STT replay targets failed: $($failedTargets -join ', '). Available reports were saved to their separate output paths."
}
