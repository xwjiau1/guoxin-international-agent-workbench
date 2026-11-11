$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'docker-env.ps1')
function Test-DockerEngine {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $DockerExecutable info --format '{{.ServerVersion}}' 2>$null | Out-Null; return ($LASTEXITCODE -eq 0) }
    finally { $ErrorActionPreference = $previousPreference }
}
if (Test-DockerEngine) { return }
$desktopExecutable = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
if (-not (Test-Path -LiteralPath $desktopExecutable)) { throw 'Docker Desktop is not installed.' }
Start-Process -FilePath $desktopExecutable -WindowStyle Hidden
for ($attempt = 0; $attempt -lt 24; $attempt++) {
    Start-Sleep -Seconds 5
    if (Test-DockerEngine) { Write-Host 'Docker engine is ready.'; return }
}
throw 'Docker did not become ready. See the D:\GuoxinAssistantRuntime\Docker logs; do not reset existing data.'
