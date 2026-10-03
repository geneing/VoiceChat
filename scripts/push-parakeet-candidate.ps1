param(
    [string]$DeviceSerial
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$modelRoot = Join-Path $repoRoot "models\parakeet-tdt-ctc-110m-en-int8"
$adb = Join-Path (Join-Path $env:LOCALAPPDATA "Android\Sdk") "platform-tools\adb.exe"
$appId = "com.voicechat.agent"
$remoteModelRoot = "/data/user/0/$appId/files/parakeet-tdt-ctc-110m-en-int8"
$temporaryRoot = "/data/local/tmp/voicechat-parakeet-$([guid]::NewGuid().ToString('N'))"

if (-not (Test-Path -LiteralPath $adb)) {
    throw "Android SDK ADB was not found at '$adb'."
}

$artifacts = @(
    [pscustomobject]@{
        Name = "model.int8.onnx"
        Sha256 = "9177a9146cf32ee0cc8152276ef95116f312018d316be37ccf57f7efea81fc1a"
    },
    [pscustomobject]@{
        Name = "tokens.txt"
        Sha256 = "450e56bd2f036fe5b6aa821865838cc5aa9d8b0106134ce9a9ba0664abe6cd10"
    }
)

$serials = @(& $adb devices | Select-String '^\S+\s+device$' | ForEach-Object { ($_ -split '\s+')[0] })
if ($LASTEXITCODE -ne 0) {
    throw "ADB could not list devices."
}
if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
    if ($serials.Count -ne 1) {
        throw "Expected exactly one authorized device; found $($serials.Count). Pass -DeviceSerial to select one."
    }
    $DeviceSerial = $serials[0]
} elseif ($DeviceSerial -notin $serials) {
    throw "Device '$DeviceSerial' is not authorized and online."
}

& $adb -s $DeviceSerial shell run-as $appId mkdir -p $remoteModelRoot
if ($LASTEXITCODE -ne 0) {
    throw "The debug app must be installed before the Parakeet model can be staged."
}
& $adb -s $DeviceSerial shell mkdir -p $temporaryRoot
if ($LASTEXITCODE -ne 0) {
    throw "Could not create the temporary model staging directory."
}

try {
    foreach ($artifact in $artifacts) {
        $hostPath = Join-Path $modelRoot $artifact.Name
        if (-not (Test-Path -LiteralPath $hostPath -PathType Leaf)) {
            throw "Missing '$hostPath'. Run scripts/fetch-parakeet-candidate.ps1 first."
        }
        $hostHash = (Get-FileHash -LiteralPath $hostPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hostHash -ne $artifact.Sha256) {
            throw "Local checksum mismatch for '$hostPath'. Re-fetch the pinned candidate before installing it."
        }

        $remotePath = "$remoteModelRoot/$($artifact.Name)"
        $existing = @(& $adb -s $DeviceSerial shell run-as $appId sha256sum $remotePath 2>$null)
        $remoteHash = if ($existing.Count -gt 0) { ($existing[0] -split '\s+')[0].ToLowerInvariant() } else { "" }
        if ($remoteHash -eq $artifact.Sha256) {
            Write-Output "Already staged: $($artifact.Name)"
            continue
        }

        $temporaryPath = "$temporaryRoot/$($artifact.Name)"
        & $adb -s $DeviceSerial push $hostPath $temporaryPath
        if ($LASTEXITCODE -ne 0) {
            throw "ADB could not stage '$($artifact.Name)'."
        }
        & $adb -s $DeviceSerial shell run-as $appId cp $temporaryPath $remotePath
        if ($LASTEXITCODE -ne 0) {
            throw "Could not copy '$($artifact.Name)' into app-private storage."
        }
        $verified = @(& $adb -s $DeviceSerial shell run-as $appId sha256sum $remotePath)
        if ($LASTEXITCODE -ne 0 -or $verified.Count -eq 0 -or ($verified[0] -split '\s+')[0].ToLowerInvariant() -ne $artifact.Sha256) {
            throw "Device checksum verification failed for '$($artifact.Name)'."
        }
        Write-Output "Staged and verified: $($artifact.Name)"
    }
} finally {
    & $adb -s $DeviceSerial shell rm -rf $temporaryRoot | Out-Null
}

Write-Output "Parakeet model files are in app-private storage on $DeviceSerial."
