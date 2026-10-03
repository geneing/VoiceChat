#!/usr/bin/env pwsh
# Score local ASR hypotheses with WER using the M25 SpeechQualityScorer.
#
# Reads test_data/litert-asr-wer/cases.csv and the matching
# <model>--<clip>.hypothesis.txt / <clip>.reference.txt files, then runs the
# host JVM harness. Optional switches build the tokenizer JNI (needed to produce
# new hypotheses on-device) and filter to clips that fit the model window.
#
# Labels and the built .so are local and Git-ignored; no audio or transcript
# enters Git.
[CmdletBinding()]
param(
    [string]$AndroidNdkHome = $env:ANDROID_NDK_HOME,
    [string]$WerDirectory = 'test_data/litert-asr-wer',
    [string]$ReportPath = 'build/litert-wer-report.csv',
    [double]$WindowSeconds = 0,
    [string]$ClipDurations = '',
    [string]$ReferenceSource = 'local_whisper',
    [switch]$BuildTokenizerJni
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

if ($BuildTokenizerJni) {
    if ([string]::IsNullOrWhiteSpace($AndroidNdkHome)) {
        $ndkRoot = Join-Path $sdkRoot 'ndk'
        if (Test-Path -LiteralPath $ndkRoot) {
            $AndroidNdkHome = (
                Get-ChildItem -LiteralPath $ndkRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1
            ).FullName
        }
    }
    if ([string]::IsNullOrWhiteSpace($AndroidNdkHome) -or -not (Test-Path -LiteralPath $AndroidNdkHome)) {
        throw 'Android NDK not found. Set ANDROID_NDK_HOME or -AndroidNdkHome.'
    }
    $env:ANDROID_NDK_HOME = $AndroidNdkHome
    $env:ANDROID_NDK_ROOT = $AndroidNdkHome
    $env:CMAKE_BIN = Join-Path $sdkRoot 'cmake\3.22.1\bin\cmake.exe'
    $env:NINJA_BIN = Join-Path $sdkRoot 'cmake\3.22.1\bin\ninja.exe'
    $ndkWsl = (wsl -e bash -lc "wslpath -u '$AndroidNdkHome'").Trim()
    Write-Output "Building tokenizer JNI with NDK $AndroidNdkHome"
    wsl -e bash -lc "cd /mnt/i/Android_Projects/VoiceChat && CMAKE_BIN=/mnt/c/Users/genei/AppData/Local/Android/Sdk/cmake/3.22.1/bin/cmake.exe NINJA_BIN=/mnt/c/Users/genei/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe ANDROID_NDK_HOME=$ndkWsl bash scripts/build-tokenizer-jni.sh"
    if ($LASTEXITCODE -ne 0) { throw "Tokenizer JNI build failed with exit code $LASTEXITCODE." }
}

$absoluteReport = if ([System.IO.Path]::IsPathRooted($ReportPath)) { $ReportPath } else { Join-Path $repoRoot $ReportPath }
$properties = @(
    "-Dvoicechat.wer.dir=$WerDirectory",
    "-Dvoicechat.wer.report=$($absoluteReport.Replace('\', '/'))",
    "-Dvoicechat.wer.reference.source=$ReferenceSource"
)
if ($WindowSeconds -gt 0) { $properties += "-Dvoicechat.wer.window.seconds=$WindowSeconds" }
if (-not [string]::IsNullOrWhiteSpace($ClipDurations)) { $properties += "-Dvoicechat.wer.clip.durations=$ClipDurations" }
$env:JAVA_TOOL_OPTIONS = ($properties -join ' ')

$env:ANDROID_HOME = $sdkRoot
# Gradle writes "Picked up JAVA_TOOL_OPTIONS" to stderr; do not treat that as a
# terminating error.
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& (Join-Path $repoRoot 'gradlew.bat') ':app:testDebugUnitTest' '--tests' 'com.voicechat.agent.eval.litert.LiteRtWerHarnessTest' '--console=plain'
$gradleExit = $LASTEXITCODE
$ErrorActionPreference = $previousErrorAction
if ($gradleExit -ne 0) { throw "WER harness run failed with exit code $gradleExit." }

if (Test-Path -LiteralPath $absoluteReport) {
    Write-Output "WER report: $absoluteReport"
    Get-Content -LiteralPath $absoluteReport
}
