param(
    # Where the markdown record is written. Relative paths are resolved against the
    # repository root (not the Gradle module directory).
    [string]$OutputPath,
    # Attempts per model configuration; the record shows the median.
    [int]$Repeats = 3,
    # The credential file to read, relative to the repository root unless absolute.
    [string]$SecretsFile = "secrets/opencode-go.key",
    [switch]$SkipGradleCheck
)

# Runs the LLM-only voice harness: the Yosemite request through every model the
# voice loop may select, with the thinking parameter recommended for each, against
# the real OpenCode Go service. It measures time to first assistant text, time to
# a speakable first sentence, and the full response, and writes the timings plus
# the verbatim answers to a markdown record.
#
# This is a HOST test (Kotlin/JVM), not a device test: no Pixel, microphone, or
# TTS is involved. The credential is read from secrets/ (git-ignored) and is only
# ever exported into the test process environment; it is never printed and never
# reaches the record.
#
# Usage (from the repository root, in PowerShell):
#   .\scripts\run-llm-harness.ps1
#   .\scripts\run-llm-harness.ps1 -OutputPath build/llm-harness/yosemite-2026-10-17.md
$ErrorActionPreference = "Continue"

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$gradle = Join-Path $repoRoot "gradlew.bat"
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk"

if (-not (Test-Path -LiteralPath $gradle)) { throw "Gradle wrapper was not found at '$gradle'." }

$credentialFile =
    if ([System.IO.Path]::IsPathRooted($SecretsFile)) { $SecretsFile } else { Join-Path $repoRoot $SecretsFile }
if (-not (Test-Path -LiteralPath $credentialFile)) {
    throw "Credential file not found at '$credentialFile'. Create it (see secrets/README.md) before running the harness."
}

# One `NAME=value` per line; only OPENCODE_API_KEY is read, and only its presence
# and length are ever reported.
$apiKey = $null
foreach ($line in Get-Content -LiteralPath $credentialFile) {
    if ($line -match '^\s*OPENCODE_API_KEY\s*=') {
        $apiKey = ($line -replace '^\s*OPENCODE_API_KEY\s*=', '').Trim()
        break
    }
}
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    throw "No OPENCODE_API_KEY entry was found in '$credentialFile' (see secrets/README.md)."
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = "build/llm-harness/voice-llm-yosemite.md"
}
# Resolve before handing it to the test: the JVM test task's working directory is
# the Gradle module (app/), not the repository root.
$recordPath =
    if ([System.IO.Path]::IsPathRooted($OutputPath)) { $OutputPath } else { Join-Path $repoRoot $OutputPath }
$env:VOICECHAT_LLM_HARNESS = "1"
$env:OPENCODE_API_KEY = $apiKey
$env:VOICECHAT_LLM_HARNESS_OUT = $recordPath
$env:VOICECHAT_LLM_HARNESS_REPEATS = "$Repeats"

Write-Host "llm harness: credential loaded from $credentialFile (length $($apiKey.Length))"
Write-Host "llm harness: record will be written to $recordPath ($Repeats attempt(s) per model)"
Write-Host "llm harness: running :app:testDebugUnitTest --tests *VoiceLlmHarnessTest"

if (-not $SkipGradleCheck) {
    & $gradle :app:testDebugUnitTest --tests "*VoiceLlmHarnessTest" --console=plain
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        throw "The LLM harness test failed (gradle exit code $code). See app/build/reports/tests/testDebugUnitTest for the report."
    }
}

if (-not (Test-Path -LiteralPath $recordPath)) {
    throw "The harness reported success but no record was written to '$recordPath'."
}
$record = Get-Item -LiteralPath $recordPath
Write-Host "llm harness: record written to $($record.FullName) ($($record.Length) bytes)"
Write-Output $record.FullName
