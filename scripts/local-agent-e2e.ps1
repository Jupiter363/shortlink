param(
    [switch] $Help,
    [switch] $SkipBuild,
    [switch] $StartRedis,
    [switch] $RunSmoke,
    [switch] $KeepRunning,
    [string] $Username = $env:AGENT_E2E_USERNAME,
    [string] $UserId = $env:AGENT_E2E_USER_ID,
    [string] $RealName = $env:AGENT_E2E_REAL_NAME,
    [string] $Gid = $env:AGENT_E2E_GID,
    [string] $StartDate = "2024-01-01",
    [string] $EndDate = "2026-12-31",
    [int] $Current = 1,
    [int] $Size = 3
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Show-Usage {
    @"
Local Agent E2E launcher

Required environment variables:
  DEEPSEEK_API_KEY
  AGENT_INTERNAL_TOKEN
  AGENT_DATASOURCE_URL
  AGENT_DATASOURCE_USERNAME
  AGENT_DATASOURCE_PASSWORD

Required smoke-test identity:
  AGENT_E2E_USERNAME / -Username
  AGENT_E2E_USER_ID / -UserId
  AGENT_E2E_REAL_NAME / -RealName
  AGENT_E2E_GID / -Gid

Example:
  .\scripts\local-agent-e2e.ps1 -StartRedis -RunSmoke -KeepRunning

Notes:
  - Secrets stay in environment variables or .env.local, never in this script.
  - Logs and generated classpath files are written under target/e2e-logs.
  - project starts through a Java argfile to avoid Windows classpath length limits.
"@ | Write-Host
}

function Require-Env([string[]] $Names) {
    foreach ($name in $Names) {
        $value = [Environment]::GetEnvironmentVariable($name, "Process")
        if ([string]::IsNullOrWhiteSpace($value)) {
            throw "Missing required environment variable: $name"
        }
    }
}

function Require-Text([string] $Name, [string] $Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "Missing required argument or environment value: $Name"
    }
}

function Wait-Port([int] $Port, [string] $Name, [int] $TimeoutSeconds = 120) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $open = Test-NetConnection -ComputerName 127.0.0.1 -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue
        if ($open) {
            Write-Host "$Name ready on 127.0.0.1:$Port"
            return
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "$Name did not become ready on 127.0.0.1:$Port within $TimeoutSeconds seconds"
}

function Start-HiddenProcess([string] $Name, [string] $FilePath, [string[]] $ArgumentList, [string] $WorkingDirectory, [string] $LogPrefix) {
    $stdout = Join-Path $script:LogDir "$LogPrefix.out.log"
    $stderr = Join-Path $script:LogDir "$LogPrefix.err.log"
    $process = Start-Process `
        -FilePath $FilePath `
        -ArgumentList $ArgumentList `
        -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru
    $script:StartedProcesses += [pscustomobject]@{
        Name = $Name
        Id = $process.Id
    }
    Write-Host "$Name started, pid=$($process.Id), logs=$LogPrefix.*.log"
    return $process
}

function Stop-StartedProcesses {
    [array]::Reverse($script:StartedProcesses)
    foreach ($processInfo in $script:StartedProcesses) {
        $process = Get-Process -Id $processInfo.Id -ErrorAction SilentlyContinue
        if ($process) {
            Write-Host "Stopping $($processInfo.Name), pid=$($processInfo.Id)"
            Stop-Process -Id $processInfo.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

function Start-RedisContainer {
    $existing = docker ps --filter "name=shortlink-agent-e2e-redis" --format "{{.Names}}"
    if ($existing) {
        Write-Host "Redis container already running: shortlink-agent-e2e-redis"
        return
    }
    $containerId = docker run -d --rm --name shortlink-agent-e2e-redis -p 6379:6379 redis:7.2-alpine
    $script:StartedRedis = $true
    Write-Host "Redis container started: $containerId"
}

function Stop-RedisContainer {
    if ($script:StartedRedis) {
        Write-Host "Stopping Redis container shortlink-agent-e2e-redis"
        docker stop shortlink-agent-e2e-redis | Out-Null
    }
}

function Build-Modules {
    mvn -pl project -DskipTests package
    mvn -pl admin -DskipTests package
    mvn -pl agent-service -DskipTests package
}

function Build-ProjectClasspathArgfile {
    mvn -pl project dependency:build-classpath "-Dmdep.outputFile=../target/e2e-logs/project.classpath"
    $classpath = "target/classes;" + (Get-Content -Encoding UTF8 -Raw (Join-Path $script:LogDir "project.classpath"))
    $argFile = Join-Path $script:LogDir "project.args"
    $lines = @(
        "-cp",
        $classpath,
        "com.nageoffer.shortlink.project.ShortLinkApplication",
        "--spring.cloud.nacos.discovery.enabled=false",
        "--spring.data.redis.host=127.0.0.1",
        "--spring.data.redis.port=6379"
    )
    [System.IO.File]::WriteAllLines($argFile, $lines, (New-Object System.Text.UTF8Encoding($false)))
    return $argFile
}

function Invoke-AgentSmoke {
    Require-Text "Username" $Username
    Require-Text "UserId" $UserId
    Require-Text "RealName" $RealName
    Require-Text "Gid" $Gid

    $headers = @{
        username = $Username
        userId = $UserId
        realName = $RealName
    }
    $message = "show groups and page short links and stats and access records for gid=$Gid startDate=$StartDate endDate=$EndDate current=$Current size=$Size Please analyze campaign performance and explain anomalies in Chinese."
    $body = @{
        sessionId = "local-agent-e2e-" + (Get-Date -Format "yyyyMMddHHmmss")
        message = $message
    } | ConvertTo-Json -Depth 6

    $response = Invoke-RestMethod `
        -Method Post `
        -Uri "http://127.0.0.1:8002/api/short-link/admin/v1/agent/chat" `
        -Headers $headers `
        -ContentType "application/json" `
        -Body $body `
        -TimeoutSec 180

    $data = $response.data
    Write-Host "Smoke success=$($response.success), code=$($response.code), session=$($data.sessionId)"
    Write-Host "Warnings: $(@($data.warnings) -join '|')"
    Write-Host "Trace: $(@($data.traceEvents | ForEach-Object { $_.nodeName + ':' + $_.status }) -join '>')"
    Write-Host "Tools: $(@($data.toolCalls | ForEach-Object { $_.name + ':' + $_.success }) -join '|')"
    foreach ($source in @($data.dataSources)) {
        if ($source.type -eq "llm") {
            Write-Host "LLM: model=$($source.model), finishReason=$($source.finishReason)"
        }
        if ($source.type -eq "tool") {
            Write-Host "Tool executions: $(@($source.executions).Count)"
        }
    }
}

if ($Help) {
    Show-Usage
    exit 0
}

$script:RootDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$script:LogDir = Join-Path $script:RootDir "target/e2e-logs"
$script:StartedProcesses = @()
$script:StartedRedis = $false

New-Item -ItemType Directory -Force $script:LogDir | Out-Null
Set-Location $script:RootDir

Require-Env @(
    "DEEPSEEK_API_KEY",
    "AGENT_INTERNAL_TOKEN",
    "AGENT_DATASOURCE_URL",
    "AGENT_DATASOURCE_USERNAME",
    "AGENT_DATASOURCE_PASSWORD"
)

try {
    if ($StartRedis) {
        Start-RedisContainer
    }
    Wait-Port 6379 "Redis" 60

    if (-not $SkipBuild) {
        Build-Modules
    }

    $projectArgFile = Build-ProjectClasspathArgfile
    Start-HiddenProcess "project" "java" @("@$projectArgFile") (Join-Path $script:RootDir "project") "project"
    Wait-Port 8001 "project"

    Start-HiddenProcess "admin" "java" @(
        "-jar",
        (Join-Path $script:RootDir "admin/target/shortlink-admin.jar"),
        "--spring.cloud.nacos.discovery.enabled=false",
        "--spring.data.redis.host=127.0.0.1",
        "--spring.data.redis.port=6379",
        "--aggregation.remote-url=http://127.0.0.1:8001",
        "--short-link.agent.admin.remote-url=http://127.0.0.1:8010",
        "--short-link.agent.admin.internal-token=$env:AGENT_INTERNAL_TOKEN"
    ) (Join-Path $script:RootDir "admin") "admin"
    Wait-Port 8002 "admin"

    Start-HiddenProcess "agent-service" "java" @(
        "-jar",
        (Join-Path $script:RootDir "agent-service/target/shortlink-agent-service.jar")
    ) (Join-Path $script:RootDir "agent-service") "agent-service"
    Wait-Port 8010 "agent-service"

    if ($RunSmoke) {
        Invoke-AgentSmoke
    }

    if ($KeepRunning) {
        Write-Host "Services are running. Press Ctrl+C to exit; stop services manually if the shell is closed."
        while ($true) {
            Start-Sleep -Seconds 30
        }
    }
} finally {
    if (-not $KeepRunning) {
        Stop-StartedProcesses
        Stop-RedisContainer
    }
}
