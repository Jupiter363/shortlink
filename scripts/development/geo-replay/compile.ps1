param(
    [Parameter(Mandatory = $true)][string] $ClassPath,
    [string] $OutputDirectory
)
$ErrorActionPreference = 'Stop'
if (-not $OutputDirectory) {
    $repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
    $OutputDirectory = Join-Path $repositoryRoot '.work/geo-replay/classes'
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
& javac -encoding UTF-8 --release 17 -cp $ClassPath -d $OutputDirectory `
    (Join-Path $PSScriptRoot 'GeoReplay.java') (Join-Path $PSScriptRoot 'GeoReplaySelfTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Geo replay compilation failed' }
& java -cp "$OutputDirectory;$ClassPath" com.jupiter.shortlink.tools.GeoReplaySelfTest
if ($LASTEXITCODE -ne 0) { throw 'Geo replay self-test failed' }
Write-Output "Compiled replay classes: $OutputDirectory"
