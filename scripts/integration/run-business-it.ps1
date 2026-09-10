param(
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateSet(3306,13306)][int]$MySqlPort = 13306,
    [string]$DatabaseUser = $env:SHORTLINK_TEST_DB_USER,
    [string]$DatabasePassword = $env:SHORTLINK_TEST_DB_PASSWORD,
    [string]$MinioAccess = $env:SHORTLINK_TEST_MINIO_ACCESS,
    [string]$MinioSecret = $env:SHORTLINK_TEST_MINIO_SECRET,
    [switch]$AllowReset
)
$ErrorActionPreference = 'Stop'
if (-not $AllowReset) { throw 'Explicit -AllowReset is required for the dedicated shortlink_*_it databases.' }
foreach ($value in @($JavaHome,$DatabaseUser,$DatabasePassword,$MinioAccess,$MinioSecret)) {
    if ([string]::IsNullOrWhiteSpace($value)) { throw 'Supply Java 17 and explicit isolated database/MinIO credentials.' }
}
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Set-Location -LiteralPath $workspace
$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome/bin;$env:Path"
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
$jdbcSuffix = '?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
$env:SHORTLINK_BUSINESS_TEST_JDBC_URL = "jdbc:mysql://127.0.0.1:$MySqlPort/shortlink_business_it$jdbcSuffix"
$env:SHORTLINK_BUSINESS_TEST_ALLOW_RESET = 'true'
$env:SHORTLINK_BUSINESS_TEST_USER = $DatabaseUser
$env:SHORTLINK_BUSINESS_TEST_PASSWORD = $DatabasePassword
$env:SHORTLINK_BATCH_TEST_JDBC_URL = "jdbc:mysql://127.0.0.1:$MySqlPort/shortlink_batch_it$jdbcSuffix"
$env:SHORTLINK_BATCH_TEST_ALLOW_RESET = 'true'
$env:SHORTLINK_BATCH_TEST_USER = $DatabaseUser
$env:SHORTLINK_BATCH_TEST_PASSWORD = $DatabasePassword
$env:SHORTLINK_BATCH_TEST_MINIO_ENDPOINT = 'http://127.0.0.1:19000'
$env:SHORTLINK_BATCH_TEST_MINIO_ACCESS = $MinioAccess
$env:SHORTLINK_BATCH_TEST_MINIO_SECRET = $MinioSecret
$env:SHORTLINK_ID_TEST_JDBC_URL = "jdbc:mysql://127.0.0.1:$MySqlPort/shortlink_id_test_v07$jdbcSuffix"
$env:SHORTLINK_ID_TEST_CATALOG = 'shortlink_id_test_v07'
$env:SHORTLINK_ID_TEST_ALLOW_RESET = 'true'
$env:SHORTLINK_ID_TEST_USER = $DatabaseUser
$env:SHORTLINK_ID_TEST_PASSWORD = $DatabasePassword
New-Item -ItemType Directory -Path '.work/verification' -Force | Out-Null
$log = Join-Path $workspace ('.work/verification/business-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '.log')
& mvn -pl :shortlink-command -am -Pintegration verify *> $log
$result = $LASTEXITCODE
Get-Content -LiteralPath $log -Tail 25
if ($result -ne 0) { throw "Business/ID integration verification failed; see $log" }
