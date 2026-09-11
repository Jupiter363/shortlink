param(
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateRange(1024,65535)][int]$MySqlPort = 13306,
    [int]$RedisPort = 16379,
    [string]$KafkaBootstrap = 'localhost:19092',
    [string]$TestUser = $env:SHORTLINK_TEST_DB_USER,
    [string]$TestPassword = $env:SHORTLINK_TEST_DB_PASSWORD,
    [string]$MySqlRootPassword = $env:SHORTLINK_MYSQL_TEST_PASSWORD,
    [switch]$AllowReset
)
$ErrorActionPreference='Stop'
if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw 'Supply Java 17 through -JavaHome or JAVA_HOME.' }
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe') -PathType Leaf)) { throw 'JavaHome must point to a Windows JDK containing bin/java.exe; Java 17 is required.' }
if (-not $AllowReset -or [string]::IsNullOrWhiteSpace($TestUser) -or [string]::IsNullOrWhiteSpace($TestPassword)) { throw 'Explicit -AllowReset and isolated MySQL credentials are required for Admin/Redirect integration.' }
if ($RedisPort -eq 6379 -or $RedisPort -lt 1024 -or $RedisPort -gt 65535) { throw 'Use an isolated non-default loopback Redis port.' }
if ([string]::IsNullOrWhiteSpace($KafkaBootstrap)) { throw 'Supply an isolated loopback Kafka bootstrap endpoint.' }
foreach ($endpoint in $KafkaBootstrap.Split(',')) {
    if ($endpoint -notmatch '^(localhost|127\.0\.0\.1|\[::1\]):([0-9]{4,5})$' -or [int]$Matches[2] -lt 1024 -or [int]$Matches[2] -gt 65535) {
        throw 'Kafka bootstrap endpoints must be explicit loopback hosts with high ports.'
    }
}
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$previousEnvironment=@{}
foreach ($name in @('JAVA_HOME','Path','JAVA_TOOL_OPTIONS','SHORTLINK_REDIS_TEST_PORT',
        'SHORTLINK_KAFKA_TEST_BOOTSTRAP','SHORTLINK_MYSQL_TEST_PORT','SHORTLINK_MYSQL_TEST_PASSWORD',
        'SHORTLINK_ADMIN_SHARD_TEST_URL','SHORTLINK_ADMIN_SHARD_TEST_USER',
        'SHORTLINK_ADMIN_SHARD_TEST_PASSWORD','SHORTLINK_ADMIN_SHARD_TEST_ALLOW_RESET')) {
    $previousEnvironment[$name]=[Environment]::GetEnvironmentVariable($name,'Process')
}
Push-Location -LiteralPath $workspace
try {
    $env:JAVA_HOME=$JavaHome
    $env:Path="$JavaHome/bin;$env:Path"
    $env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
    $env:SHORTLINK_REDIS_TEST_PORT=[string]$RedisPort
    $env:SHORTLINK_KAFKA_TEST_BOOTSTRAP=$KafkaBootstrap
    $env:SHORTLINK_MYSQL_TEST_PORT=[string]$MySqlPort
    if ([string]::IsNullOrWhiteSpace($MySqlRootPassword)) { $MySqlRootPassword = $TestPassword }
    $env:SHORTLINK_MYSQL_TEST_PASSWORD=$MySqlRootPassword
    $env:SHORTLINK_ADMIN_SHARD_TEST_URL="jdbc:mysql://127.0.0.1:$MySqlPort/shortlink_admin_sharding_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    $env:SHORTLINK_ADMIN_SHARD_TEST_USER=$TestUser
    $env:SHORTLINK_ADMIN_SHARD_TEST_PASSWORD=$TestPassword
    $env:SHORTLINK_ADMIN_SHARD_TEST_ALLOW_RESET='true'
    $output=Join-Path $workspace '.work/component-results'
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $stamp=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    $log=Join-Path $output "admin-redirect-it-$stamp.log"
    & mvn -pl ':shortlink-admin,:shortlink-redirect' -am -Pintegration verify "-Daccount.it.jdbc-url=jdbc:mysql://127.0.0.1:$MySqlPort/shortlink_account_it" "-Daccount.it.user=$TestUser" "-Daccount.it.password=$TestPassword" "-Daccount.it.redis-port=$RedisPort" *> $log
    if($LASTEXITCODE -ne 0){Get-Content -LiteralPath $log -Tail 35;throw 'Admin/Redirect isolated integration verification failed'}
    Get-Content -LiteralPath $log -Tail 25
} finally {
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name,$previousEnvironment[$name],'Process')
    }
    Pop-Location
}
