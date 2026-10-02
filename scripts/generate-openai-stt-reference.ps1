[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Low')]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixtureDirectory = [System.IO.Path]::GetFullPath((Join-Path $repoRoot 'test_data'))
$outputDirectory = Join-Path $fixtureDirectory 'openai-stt-reference-review'
$endpoint = 'https://api.openai.com/v1/audio/transcriptions'
$model = 'gpt-4o-transcribe'
$maximumAttempts = 3

$fixtures = @(
    Get-ChildItem -LiteralPath $fixtureDirectory -File -Filter 'Walking*.m4a' |
        Sort-Object -Property Name
)

if ($fixtures.Count -eq 0) {
    Write-Output 'fixtureId=none requestMs=0 totalMs=0 output=none'
    return
}

$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromMinutes(5)
$apiKey = [System.Environment]::GetEnvironmentVariable('OPENAI_API_KEY')
if (-not [string]::IsNullOrWhiteSpace($apiKey)) {
    $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $apiKey)
}

$gitCommit = $null
try {
    $gitOutput = & git -C $repoRoot rev-parse HEAD 2>$null
    if ($LASTEXITCODE -eq 0) {
        $gitCommit = ([string]$gitOutput).Trim()
    }
}
catch {
    # The commit is supplementary provenance. Do not let a missing Git executable block transcription.
}

try {
    foreach ($fixture in $fixtures) {
        $fixturePath = [System.IO.Path]::GetFullPath($fixture.FullName)
        $testRootPrefix = $fixtureDirectory.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
        if (-not $fixturePath.StartsWith($testRootPrefix, [System.StringComparison]::OrdinalIgnoreCase) -or
            $fixture.Name -notlike 'Walking*.m4a') {
            throw 'Fixture path is outside the permitted Walking recording set.'
        }

        if (-not $PSCmdlet.ShouldProcess($fixture.Name, "upload to OpenAI $model for reference transcription")) {
            continue
        }

        if ([string]::IsNullOrWhiteSpace($apiKey)) {
            throw 'OPENAI_API_KEY is not set. No audio was uploaded.'
        }

        $audioHash = (Get-FileHash -LiteralPath $fixturePath -Algorithm SHA256).Hash.ToLowerInvariant()
        $fixtureId = $audioHash.Substring(0, 16)
        $runId = [Guid]::NewGuid().ToString('N')
        $startedAtUtc = [DateTimeOffset]::UtcNow
        $allAttemptsTimer = [System.Diagnostics.Stopwatch]::StartNew()
        $attemptDiagnostics = [System.Collections.Generic.List[object]]::new()
        $transcriptText = $null
        $responseUsage = $null
        $responseLanguage = $null
        $requestId = $null
        $httpStatus = $null
        $lastRequestDurationMs = 0L
        $failureCategory = $null

        for ($attempt = 1; $attempt -le $maximumAttempts; $attempt++) {
            $request = $null
            $response = $null
            $requestTimer = [System.Diagnostics.Stopwatch]::StartNew()
            $attemptStatus = $null
            $attemptRequestId = $null
            $attemptFailure = $null
            $shouldRetry = $false

            try {
                $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $endpoint)
                $multipart = [System.Net.Http.MultipartFormDataContent]::new()

                $modelContent = [System.Net.Http.StringContent]::new($model)
                $multipart.Add($modelContent, 'model')

                $fileStream = [System.IO.FileStream]::new(
                    $fixturePath,
                    [System.IO.FileMode]::Open,
                    [System.IO.FileAccess]::Read,
                    [System.IO.FileShare]::Read
                )
                $fileContent = [System.Net.Http.StreamContent]::new($fileStream)
                $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('audio/mp4')
                $multipart.Add($fileContent, 'file', $fixture.Name)
                $request.Content = $multipart

                $response = $client.SendAsync($request).GetAwaiter().GetResult()
                $attemptStatus = [int]$response.StatusCode
                $httpStatus = $attemptStatus
                if ($response.Headers.Contains('x-request-id')) {
                    $attemptRequestId = [string]($response.Headers.GetValues('x-request-id') | Select-Object -First 1)
                    $requestId = $attemptRequestId
                }

                $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                if ($response.IsSuccessStatusCode) {
                    $decoded = ConvertFrom-Json -InputObject $body
                    if ($null -eq $decoded -or $null -eq $decoded.text) {
                        throw 'The transcription response did not contain text.'
                    }

                    $transcriptText = [string]$decoded.text
                    if ($decoded.PSObject.Properties.Name -contains 'usage') {
                        $responseUsage = $decoded.usage
                    }
                    if ($decoded.PSObject.Properties.Name -contains 'language') {
                        $responseLanguage = $decoded.language
                    }
                }
                else {
                    $shouldRetry = ($attemptStatus -eq 408) -or ($attemptStatus -eq 429) -or ($attemptStatus -ge 500 -and $attemptStatus -lt 600)
                    $attemptFailure = 'HttpStatus'
                    $failureCategory = $attemptFailure
                }
            }
            catch {
                $attemptFailure = $_.Exception.GetType().Name
                $failureCategory = $attemptFailure
                $shouldRetry = ($_.Exception -is [System.Net.Http.HttpRequestException]) -or
                    ($_.Exception -is [System.Threading.Tasks.TaskCanceledException])
            }
            finally {
                $requestTimer.Stop()
                $lastRequestDurationMs = $requestTimer.ElapsedMilliseconds
                $attemptDiagnostics.Add([ordered]@{
                    attempt = $attempt
                    httpStatus = $attemptStatus
                    requestId = $attemptRequestId
                    durationMs = $lastRequestDurationMs
                    failureCategory = $attemptFailure
                })
                if ($null -ne $response) { $response.Dispose() }
                if ($null -ne $request) { $request.Dispose() }
            }

            if ($null -ne $transcriptText) {
                $failureCategory = $null
                break
            }

            if (-not $shouldRetry -or $attempt -eq $maximumAttempts) {
                break
            }

            Start-Sleep -Seconds ([Math]::Min([Math]::Pow(2, $attempt - 1), 4))
        }

        $allAttemptsTimer.Stop()
        $completedAtUtc = [DateTimeOffset]::UtcNow
        $status = if ($null -ne $transcriptText) { 'success' } else { 'failed' }
        $draft = [ordered]@{
            schemaVersion = 1
            runId = $runId
            fixtureId = $fixtureId
            gitCommit = $gitCommit
            referenceStatus = $status
            reviewStatus = 'draft_pending_human_review'
            source = [ordered]@{
                fileName = $fixture.Name
                sha256 = $audioHash
                byteLength = $fixture.Length
                mediaType = 'audio/mp4'
            }
            reference = [ordered]@{
                provider = 'OpenAI API'
                endpoint = '/v1/audio/transcriptions'
                model = $model
                startedAtUtc = $startedAtUtc.ToString('o')
                completedAtUtc = $completedAtUtc.ToString('o')
                requestDurationMs = $lastRequestDurationMs
                totalDurationMs = $allAttemptsTimer.ElapsedMilliseconds
                attemptCount = $attemptDiagnostics.Count
                httpStatus = $httpStatus
                requestId = $requestId
                responseLanguage = $responseLanguage
                responseUsage = $responseUsage
                attemptDiagnostics = @($attemptDiagnostics)
                failureCategory = $failureCategory
                transcriptDraft = $transcriptText
            }
        }

        if (-not (Test-Path -LiteralPath $outputDirectory -PathType Container)) {
            New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
        }
        $timestamp = $completedAtUtc.ToString('yyyyMMddTHHmmssfffZ')
        $outputPath = Join-Path $outputDirectory "$fixtureId-$timestamp.json"
        $draft | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $outputPath -Encoding UTF8

        Write-Output ("fixtureId={0} requestMs={1} totalMs={2} output={3}" -f `
            $fixtureId, $lastRequestDurationMs, $allAttemptsTimer.ElapsedMilliseconds, $outputPath)

        if ($status -ne 'success') {
            throw "Reference transcription failed for fixture $fixtureId. See the local review file at $outputPath."
        }
    }
}
finally {
    $client.Dispose()
    $apiKey = $null
}
