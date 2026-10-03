param(
    [string]$DeviceSerial
)

# Stages the five pinned Parakeet-EOU-120M ONNX files into the debug app's
# app-private storage (files/parakeet-eou), verifying each against its pinned
# SHA-256 on both the host and the device. Run scripts/fetch-models.sh (or the
# host download in docs/parakeet-eou-streaming.md) first to populate
# models/parakeet-eou/. No model file is ever committed or bundled in the APK.
$ErrorActionPreference = "Continue"
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$modelRoot = Join-Path $repoRoot "models\parakeet-eou"
$adb = Join-Path (Join-Path $env:LOCALAPPDATA "Android\Sdk") "platform-tools\adb.exe"
$appId = "com.voicechat.agent"
$remoteModelRoot = "/data/user/0/$appId/files/parakeet-eou"
$temporaryRoot = "/data/local/tmp/voicechat-parakeet-eou-$([guid]::NewGuid().ToString('N'))"

if (-not (Test-Path -LiteralPath $adb)) {
    throw "Android SDK ADB was not found at '$adb'."
}

# Pinned identity: see docs/parakeet-eou-streaming.md and ParakeetEouArtifact.kt.
$artifacts = @(
    [pscustomobject]@{
        Name = "parakeet-eou-encoder.onnx"
        Sha256 = "fcd0452f27a49d266a7b13a048f603798c66afe453838cd1ac685b30c1bae55c"
    },
    [pscustomobject]@{
        Name = "parakeet-eou-decoder.onnx"
        Sha256 = "205cd7d44b5320f81d82dd6683e49e05ba4d72e0564151eb21a86e172f69f488"
    },
    [pscustomobject]@{
        Name = "parakeet-eou-joint.onnx"
        Sha256 = "6fb4b7674c2988ff8a4aa9c9181c6f06990d318a828e9696c4619505d2c08457"
    },
    [pscustomobject]@{
        Name = "config.json"
        Sha256 = "db0d95f8575e6610f4f524c87d647d5bcd5a868ed5102044e5ccdf4352d5da4b"
    },
    [pscustomobject]@{
        Name = "vocab.json"
        Sha256 = "83fd42ad33dae1bd3ceee6c0bb6c625f314cf0b2dc8430be441ac1e2643d5c36"
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
    throw "The debug app must be installed before the Parakeet-EOU model can be staged."
}
& $adb -s $DeviceSerial shell mkdir -p $temporaryRoot
if ($LASTEXITCODE -ne 0) {
    throw "Could not create the temporary model staging directory."
}

try {
    foreach ($artifact in $artifacts) {
        $hostPath = Join-Path $modelRoot $artifact.Name
        if (-not (Test-Path -LiteralPath $hostPath -PathType Leaf)) {
            throw "Missing '$hostPath'. Fetch the pinned Parakeet-EOU files first (docs/parakeet-eou-streaming.md)."
        }
        $hostHash = (Get-FileHash -LiteralPath $hostPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hostHash -ne $artifact.Sha256) {
            throw "Local checksum mismatch for '$hostPath'. Re-fetch the pinned model set before installing it."
        }

        $remotePath = "$remoteModelRoot/$($artifact.Name)"
        $existing = @(& $adb -s $DeviceSerial shell "run-as $appId sha256sum $remotePath 2>/dev/null")
        $remoteHash = if ($existing.Count -gt 0 -and $existing[0] -match '^[0-9a-fA-F]{64}') { ($existing[0] -split '\s+')[0].ToLowerInvariant() } else { "" }
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

Write-Output "Parakeet-EOU model files are in app-private storage on $DeviceSerial."
