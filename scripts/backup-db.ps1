param([string]$OutputDirectory = 'D:\GuoxinAssistantRuntime\backups')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'docker-env.ps1')
$backupToken = [Guid]::NewGuid().ToString('N')
$backupStamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + 'Z'
$containerDumpPath = '/tmp/guoxin-backup-' + $backupToken + '.dump'
$containerId = $null
Push-Location -LiteralPath $projectRoot
try {
    $containerIds = @(& $DockerExecutable compose ps -q postgres)
    if ($LASTEXITCODE -ne 0 -or $containerIds.Count -ne 1 -or [string]::IsNullOrWhiteSpace($containerIds[0])) { throw 'Exactly one project PostgreSQL container must be running.' }
    $containerId = $containerIds[0]
    $containerId = $containerId.Trim()
    $isRunning = & $DockerExecutable inspect --format '{{.State.Running}}' $containerId
    if ($LASTEXITCODE -ne 0 -or $isRunning -ne 'true') { throw 'The project PostgreSQL container is not running.' }
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $resolvedOutput = (Resolve-Path -LiteralPath $OutputDirectory).Path
    $destination = Join-Path $resolvedOutput ('guoxin-' + $backupStamp + '-' + $backupToken.Substring(0,8) + '.dump')
    if (Test-Path -LiteralPath $destination) { throw 'Refusing to overwrite an existing backup.' }

    # pg_dump takes a transaction-consistent snapshot. Write the custom archive in
    # the Linux container, then docker cp bytes; never pipe archive data through PS.
    & $DockerExecutable exec $containerId pg_dump -U guoxin -d guoxin --format=custom --no-owner --no-privileges --file=$containerDumpPath
    if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed.' }
    & $DockerExecutable exec $containerId pg_restore --list $containerDumpPath | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'pg_restore could not read the new backup archive.' }
    & $DockerExecutable cp ($containerId + ':' + $containerDumpPath) $destination
    if ($LASTEXITCODE -ne 0) { throw 'Copying backup from the container failed.' }
    if ((Get-Item -LiteralPath $destination).Length -eq 0) { throw 'The copied backup is empty.' }
    $checksum = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    Set-Content -LiteralPath ($destination + '.sha256') -Value ($checksum + '  ' + [IO.Path]::GetFileName($destination)) -Encoding ASCII
    Write-Output ('Backup created: ' + $destination)
    Write-Output ('SHA256: ' + $checksum)
    Write-Output 'Archive listing validated. A restore drill into a separate empty database is still required to prove restorability.'
} finally {
    try {
        if ($containerId -and $containerDumpPath -match '^/tmp/guoxin-backup-[a-f0-9]{32}\.dump$') {
            $cleanupPreference = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try { & $DockerExecutable exec $containerId rm -f -- $containerDumpPath 2>$null | Out-Null }
            finally { $ErrorActionPreference = $cleanupPreference }
        }
    } finally { Pop-Location }
}
