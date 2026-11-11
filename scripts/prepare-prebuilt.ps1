$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $projectRoot 'backend\assistant-boot\target\assistant-boot-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar) -or (Get-Item -LiteralPath $jar).Length -lt 1000000) { throw 'Build the backend executable JAR first.' }
$dist = Join-Path $projectRoot 'frontend\dist'
if (-not (Test-Path -LiteralPath (Join-Path $dist 'index.html'))) { throw 'Build the frontend first.' }
$backendContext = Join-Path $projectRoot '.runtime\prebuilt\backend'
$frontendContext = Join-Path $projectRoot '.runtime\prebuilt\frontend'
New-Item -ItemType Directory -Force -Path $backendContext,$frontendContext | Out-Null
Copy-Item -LiteralPath $jar -Destination (Join-Path $backendContext 'app.jar') -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'capabilities'),(Join-Path $projectRoot 'prompts') -Destination $backendContext -Recurse -Force
Copy-Item -LiteralPath $dist -Destination $frontendContext -Recurse -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'deploy\nginx.conf') -Destination $frontendContext -Force
Write-Host 'Prepared verified build artifacts for container runtime (no host application process).'
