param(
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$modelsRoot = Join-Path $repoRoot "models"
$parakeetRoot = Join-Path $modelsRoot "parakeet-tdt-ctc-110m-en-int8"

New-Item -ItemType Directory -Path $modelsRoot -Force | Out-Null
New-Item -ItemType Directory -Path $parakeetRoot -Force | Out-Null

$artifacts = @(
    [pscustomobject]@{
        Name = "sherpa-onnx 1.13.8 Android AAR"
        Path = Join-Path $modelsRoot "sherpa-onnx-1.13.8.aar"
        Url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
        Sha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
    },
    [pscustomobject]@{
        Name = "Parakeet TDT-CTC 110M int8 model"
        Path = Join-Path $parakeetRoot "model.int8.onnx"
        Url = "https://huggingface.co/VocaHQ/sherpa-onnx-nemo-parakeet-tdt-ctc-110m-en-int8/resolve/43869f7/model.int8.onnx?download=true"
        Sha256 = "9177a9146cf32ee0cc8152276ef95116f312018d316be37ccf57f7efea81fc1a"
    },
    [pscustomobject]@{
        Name = "Parakeet tokens"
        Path = Join-Path $parakeetRoot "tokens.txt"
        Url = "https://huggingface.co/VocaHQ/sherpa-onnx-nemo-parakeet-tdt-ctc-110m-en-int8/resolve/43869f7/tokens.txt?download=true"
        Sha256 = "450e56bd2f036fe5b6aa821865838cc5aa9d8b0106134ce9a9ba0664abe6cd10"
    }
)

foreach ($artifact in $artifacts) {
    $partialPath = "$($artifact.Path).partial"
    if (Test-Path -LiteralPath $artifact.Path) {
        $existingHash = (Get-FileHash -LiteralPath $artifact.Path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($existingHash -eq $artifact.Sha256) {
            Write-Output "Verified: $($artifact.Name)"
            continue
        }
        if (-not $Force) {
            throw "Checksum mismatch for '$($artifact.Path)'. Inspect it or rerun with -Force to replace this exact artifact."
        }
        Remove-Item -LiteralPath $artifact.Path -Force
    }

    Remove-Item -LiteralPath $partialPath -Force -ErrorAction SilentlyContinue
    Write-Output "Downloading $($artifact.Name)..."
    Invoke-WebRequest -Uri $artifact.Url -OutFile $partialPath -TimeoutSec 900
    $downloadHash = (Get-FileHash -LiteralPath $partialPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($downloadHash -ne $artifact.Sha256) {
        Remove-Item -LiteralPath $partialPath -Force
        throw "Checksum verification failed for '$($artifact.Name)': got $downloadHash."
    }
    Move-Item -LiteralPath $partialPath -Destination $artifact.Path
    Write-Output "Verified: $($artifact.Name)"
}

Write-Output "Parakeet candidate artifacts are pinned and ready under models/."
