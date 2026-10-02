param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("parakeet-tdt", "parakeet-ctc", "moonshine", "whisper-tiny", "whisper-small")]
    [string] $Model,

    [Parameter(Mandatory = $true)]
    [ValidateSet("cpu", "gpu", "npu")]
    [string] $Backend,

    [Parameter(Mandatory = $true)]
    [ValidateSet("pixel-10", "galaxy-s23", "galaxy-s24", "other")]
    [string] $DeviceTarget,

    [string] $ModelArtifact
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$commit = (& git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $commit -notmatch "^[0-9a-fA-F]{40}$") {
    throw "Could not read the current full git commit hash."
}
$gitStatus = @(& git -C $repoRoot status --porcelain)
$workingTreeDirty = $gitStatus.Count -gt 0

$laneClassification = "unavailable"
if ($Backend -in @("cpu", "gpu")) {
    if ($Model -eq "whisper-small") {
        $laneClassification = "extension_candidate"
    } else {
        $laneClassification = "sample_listed"
    }
} elseif ($Model -eq "parakeet-tdt" -and $DeviceTarget -eq "pixel-10") {
    $laneClassification = "sample_listed"
} elseif ($Model -eq "parakeet-ctc" -and $DeviceTarget -in @("galaxy-s23", "galaxy-s24")) {
    $laneClassification = "sample_listed"
}

$outcome = if ($laneClassification -eq "unavailable") { "unavailable" } else { "not_implemented" }
$artifactFound = $false
$artifactSha256 = $null
$artifactIsIgnored = $null
if ($ModelArtifact) {
    if ([System.IO.Path]::IsPathRooted($ModelArtifact)) {
        throw "ModelArtifact must be a repository-relative path."
    }
    $artifactFullPath = [System.IO.Path]::GetFullPath((Join-Path $repoRoot $ModelArtifact))
    $repoPrefix = $repoRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $artifactFullPath.StartsWith($repoPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "ModelArtifact must be inside the repository's ignored models directory."
    }
    $relativeArtifactPath = $artifactFullPath.Substring($repoPrefix.Length).Replace("\", "/")
    if (-not $relativeArtifactPath.Replace("\", "/").StartsWith("models/")) {
        throw "ModelArtifact must be inside models/."
    }
    $artifactFound = Test-Path -LiteralPath $artifactFullPath -PathType Leaf
    if ($artifactFound) {
        & git -C $repoRoot check-ignore --quiet -- $relativeArtifactPath
        $artifactIsIgnored = ($LASTEXITCODE -eq 0)
        if (-not $artifactIsIgnored) {
            throw "Refusing to inspect a model artifact that Git does not ignore."
        }
        $artifactSha256 = (Get-FileHash -LiteralPath $artifactFullPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
$adbExecutable = if ($adbCommand) { $adbCommand.Source } else { $null }
if (-not $adbExecutable) {
    $sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    $sdkAdb = Join-Path $sdkRoot "platform-tools\adb.exe"
    if (Test-Path -LiteralPath $sdkAdb -PathType Leaf) {
        $adbExecutable = $sdkAdb
    }
}
$adbAvailable = $null -ne $adbExecutable
$connectedDeviceCount = $null
if ($adbAvailable) {
    $deviceOutput = @(& $adbExecutable devices 2>$null)
    $connectedDeviceCount = @(
        $deviceOutput | Where-Object { $_ -match "^[^\s]+\s+device$" }
    ).Count
}

# This M1 runner is preflight-only. It does not copy audio or models to a device,
# start inference, or create performance measurements. A future runtime-integrated
# runner will replace NOT_IMPLEMENTED only after an actual model invocation.
[ordered]@{
    schemaVersion = 1
    gitCommit = $commit
    workingTreeDirty = $workingTreeDirty
    selectedModel = $Model
    requestedBackend = $Backend
    declaredDeviceTarget = $DeviceTarget
    laneClassification = $laneClassification
    outcome = $outcome
    artifactFound = $artifactFound
    artifactSha256 = $artifactSha256
    artifactGitIgnored = $artifactIsIgnored
    adbAvailable = $adbAvailable
    connectedDeviceCount = $connectedDeviceCount
    observedBackends = @()
    timingMeasurements = $null
    runMode = "preflight_only"
} | ConvertTo-Json -Compress
