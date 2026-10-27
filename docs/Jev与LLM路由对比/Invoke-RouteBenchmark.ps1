param(
    [Parameter(Mandatory=$true)][string]$Config,
    [switch]$Live
)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskConfig = [IO.Path]::GetFullPath($Config)
if (!(Test-Path -LiteralPath $taskConfig -PathType Leaf)) { throw 'Config file does not exist' }
$taskData = Get-Content -Raw -LiteralPath $taskConfig | ConvertFrom-Json
if (($taskData.mode -eq 'LIVE') -and !$Live) { throw 'LIVE config requires -Live and approved local config' }
if (($taskData.mode -ne 'LIVE') -and $Live) { throw '-Live requires a LIVE config; do a separate DRY_RUN first' }
$taskMaven = 'D:/Tools/maven/apache-maven-3.9.11/bin/mvn.cmd'
$taskLogDirectory = Join-Path $taskRoot 'tj-aigc/target/route-benchmark'
New-Item -ItemType Directory -Force -Path $taskLogDirectory | Out-Null
$taskLog = Join-Path $taskLogDirectory ('launcher-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,6) + '.log')
$taskArgs = @('-o','-pl','tj-aigc','-am','-Dmaven.repo.local=D:/Tools/maven/maven-repository','-Dtest=com.tianji.aigc.benchmark.RouteBenchmarkLauncherTest','-Dsurefire.failIfNoSpecifiedTests=false','-Dbenchmark.run=true',('-Dbenchmark.root=' + $taskRoot),('-Dbenchmark.config=' + $taskConfig),('-Dlogback.configurationFile=' + (Join-Path $PSScriptRoot 'logback-benchmark.xml')))
if ($Live) { $taskArgs += '-Dbenchmark.live=true' }
$taskArgs += 'test'
Push-Location $taskRoot
try {
    & $taskMaven @taskArgs *> $taskLog
    $taskExit = $LASTEXITCODE
    Write-Output ('Launcher stdout/stderr: ' + $taskLog)
    Get-Content -LiteralPath $taskLog -Tail 25
    if ($taskExit -ne 0) { throw ('Benchmark launcher failed, Maven exit=' + $taskExit) }
} finally { Pop-Location }
