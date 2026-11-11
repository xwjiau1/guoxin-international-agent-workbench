param([switch]$Prebuilt)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
. (Join-Path $PSScriptRoot 'start-docker.ps1')
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    $dockerCandidate = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
    $userDockerCandidate = Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe'
    if (Test-Path -LiteralPath $dockerCandidate) { $dockerCommand = $dockerCandidate }
    elseif (Test-Path -LiteralPath $userDockerCandidate) { $dockerCommand = $userDockerCandidate }
    else { throw 'Docker Desktop is required. See docs/06-运行与验证.md.' }
} else { $dockerCommand = (Get-Command docker).Source }
& $dockerCommand info --format '{{.ServerVersion}}'
if ($LASTEXITCODE -ne 0) { throw 'Docker engine is unavailable. Start Docker Desktop and verify WSL 2.' }
if ($Prebuilt) {
    & (Join-Path $PSScriptRoot 'prepare-prebuilt.ps1')
    & $dockerCommand compose -f compose.yaml -f compose.prebuilt.yaml up -d --build
} else { & $dockerCommand compose up -d --build }
if ($LASTEXITCODE -ne 0) { throw 'Compose startup failed. Check docker compose logs.' }
Write-Host 'Workbench: http://localhost:5173'
Write-Host 'Demo accounts: DEMO001 / DEMO002 / DEMO003 ; password: Demo@123'
