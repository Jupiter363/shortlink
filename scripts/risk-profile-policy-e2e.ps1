param(
    [string] $AgentUrl = "http://127.0.0.1:8010",
    [string] $GatewayUrl = "http://127.0.0.1:8000",
    [string] $Gid = "default",
    [string] $Domain = "nurl.ink",
    [string] $ShortUri = "abc123",
    [string] $Username = "e2e"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function New-AgentHeaders {
    $headers = @{
        "X-Agent-Username" = $Username
    }
    if (-not [string]::IsNullOrWhiteSpace($env:AGENT_INTERNAL_TOKEN)) {
        $headers["X-Agent-Internal-Token"] = $env:AGENT_INTERNAL_TOKEN
    } else {
        Write-Warning "AGENT_INTERNAL_TOKEN is empty. This only works when agent-service explicitly enables internal-token dev mode."
    }
    return $headers
}

function Assert-AgentSuccess([object] $Response, [string] $StepName) {
    if ($null -eq $Response) {
        throw "$StepName returned empty response"
    }
    if ($Response.PSObject.Properties.Name -contains "success" -and -not $Response.success) {
        throw "$StepName failed: code=$($Response.code), message=$($Response.message)"
    }
}

function Show-Json([object] $Value) {
    $Value | ConvertTo-Json -Depth 12
}

$headers = New-AgentHeaders

Write-Host "1. health check agent-service"
$health = Invoke-RestMethod `
    -Uri "$AgentUrl/internal/short-link-agent/v1/health" `
    -Headers $headers `
    -Method Get
Assert-AgentSuccess $health "agent-service health"
Show-Json $health

Write-Host "2. trigger risk profile batch"
$batch = Invoke-RestMethod `
    -Uri "$AgentUrl/internal/short-link-agent/v1/risk/profiles/run-once" `
    -Headers $headers `
    -Method Post
Assert-AgentSuccess $batch "risk profile run-once"
Show-Json $batch

if ($batch.data.scannedShortLinks -le 0) {
    Write-Warning "run-once scannedShortLinks is 0. Check project/admin data and whether recent 7-day access records exist."
}

Write-Host "3. query group risk overview"
$overview = Invoke-RestMethod `
    -Uri "$AgentUrl/internal/short-link-agent/v1/risk/groups/$Gid/overview" `
    -Headers $headers `
    -Method Get
Assert-AgentSuccess $overview "group risk overview"
Show-Json $overview

Write-Host "4. call gateway short link"
try {
    $gatewayResponse = Invoke-WebRequest `
        -Uri "$GatewayUrl/$ShortUri" `
        -Headers @{ "Host" = $Domain } `
        -MaximumRedirection 0 `
        -ErrorAction Stop
    Write-Host "gateway status=$($gatewayResponse.StatusCode)"
} catch {
    $response = $_.Exception.Response
    if ($null -ne $response) {
        Write-Host "gateway status=$([int]$response.StatusCode)"
    } else {
        throw
    }
}
