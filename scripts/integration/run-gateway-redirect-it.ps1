param([string]$JavaHome='D:/develop/javaJDK/17')
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Set-Location -LiteralPath $workspace
$env:JAVA_HOME=$JavaHome
$env:Path="$JavaHome/bin;$env:Path"
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
$env:SHORTLINK_REDIS_TEST_PORT='16379'
$env:SHORTLINK_KAFKA_TEST_BOOTSTRAP='localhost:19092'
$env:SHORTLINK_MYSQL_TEST_PORT='13306'
$env:SHORTLINK_MYSQL_TEST_PASSWORD='shortlink-it-only'
$output=Join-Path $workspace '.work/component-results'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$stamp=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$log=Join-Path $output "gateway-redirect-it-$stamp.log"
& mvn -pl gateway,shortlink-redirect -Pintegration verify *> $log
if($LASTEXITCODE -ne 0){Get-Content -LiteralPath $log -Tail 35;throw 'Gateway/Redirect isolated integration verification failed'}
Get-Content -LiteralPath $log -Tail 25
