$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$drive=$workspace.Substring(0,1).ToLowerInvariant()
$linux='/mnt/'+$drive+$workspace.Substring(2).Replace('\','/')+'/deploy/monitoring'
$output=Join-Path $workspace '.work/component-results'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$log=Join-Path $output ('promtool-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')+'.log')
& wsl -d shortlink-refactor-it --exec docker run --rm -v "${linux}:/etc/prometheus:ro" --entrypoint /bin/promtool prom/prometheus:v3.2.1 check config /etc/prometheus/prometheus.yml *> $log
$result=$LASTEXITCODE
Get-Content -LiteralPath $log
if($result -ne 0){throw 'Official promtool rejected the monitoring configuration'}
