param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Maven = 'mvn',
    [ValidateSet(3306,13306)][int]$MysqlPort = 3306,
    [int]$RedisPort = 16379,
    [string]$TestUser = 'shortlink_refactor_it',
    [string]$TestPassword = $env:SHORTLINK_IT_PASSWORD,
    [switch]$AllowReset
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if (-not $AllowReset -or [string]::IsNullOrWhiteSpace($TestPassword) -or [string]::IsNullOrWhiteSpace($JavaHome)) { throw 'Explicit -AllowReset, Java 17 and isolated test credentials are required.' }
if ($RedisPort -eq 6379 -or $RedisPort -lt 1024 -or $RedisPort -gt 65535) { throw 'An isolated non-default loopback Redis port is required.' }
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$env:JAVA_HOME = $JavaHome
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
$env:AGENT_TEST_MYSQL_URL = "jdbc:mysql://127.0.0.1:$MysqlPort/shortlink_agent_it"
$env:AGENT_TEST_MYSQL_USER = $TestUser
$env:AGENT_TEST_MYSQL_PASSWORD = $TestPassword
$env:SHORTLINK_ADMIN_SHARD_TEST_URL = "jdbc:mysql://127.0.0.1:$MysqlPort/shortlink_admin_sharding_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
$env:SHORTLINK_ADMIN_SHARD_TEST_USER = $TestUser
$env:SHORTLINK_ADMIN_SHARD_TEST_PASSWORD = $TestPassword
# These names are fixed isolated test catalogs; the fixtures reject other schemas and reset their own test tables.
$env:SHORTLINK_ADMIN_SHARD_TEST_ALLOW_RESET = 'true'
New-Item -ItemType Directory -Path (Join-Path $repoRoot '.work/verification') -Force | Out-Null
Push-Location $repoRoot
try {
    & $Maven -pl :shortlink-agent-service -am -Pintegration verify 2>&1 | Tee-Object -FilePath (Join-Path $repoRoot '.work/verification/agent-rerun.log')
    if ($LASTEXITCODE -ne 0) { throw 'Agent unit/integration verification failed; inspect agent-rerun.log.' }
    & $Maven -pl :shortlink-admin -am -Pintegration verify "-Daccount.it.jdbc-url=jdbc:mysql://127.0.0.1:$MysqlPort/shortlink_account_it" "-Daccount.it.user=$TestUser" "-Daccount.it.password=$TestPassword" "-Daccount.it.redis-port=$RedisPort" 2>&1 | Tee-Object -FilePath (Join-Path $repoRoot '.work/verification/admin-rerun.log')
    if ($LASTEXITCODE -ne 0) { throw 'Admin unit/integration verification failed; inspect admin-rerun.log.' }
} finally { Pop-Location }
