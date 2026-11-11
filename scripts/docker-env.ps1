# Dot-source for this project's Docker commands. Only the current process and its
# children are affected; no user/machine environment values or files are moved.
# This host redirects/encrypts C:\Users paths and rejects same-directory rename.
$dockerRuntimeRoot = 'D:\GuoxinAssistantRuntime\Docker'
$dockerDirectories = @{
    APPDATA = Join-Path $dockerRuntimeRoot 'Roaming'
    LOCALAPPDATA = Join-Path $dockerRuntimeRoot 'Local'
    TEMP = Join-Path $dockerRuntimeRoot 'Temp'
    TMP = Join-Path $dockerRuntimeRoot 'Temp'
    DOCKER_CONFIG = Join-Path $dockerRuntimeRoot 'CliConfig'
}
foreach ($dockerEntry in $dockerDirectories.GetEnumerator()) {
    New-Item -ItemType Directory -Path $dockerEntry.Value -Force | Out-Null
    [Environment]::SetEnvironmentVariable($dockerEntry.Key, $dockerEntry.Value, 'Process')
}
$DockerExecutable = Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin\docker.exe'
if (-not (Test-Path -LiteralPath $DockerExecutable -PathType Leaf)) { throw 'Docker Desktop is not installed. Run install-docker.ps1 first.' }
$dockerBinaryDirectory = Split-Path -Parent $DockerExecutable
if (($env:PATH -split ';') -notcontains $dockerBinaryDirectory) { $env:PATH = $dockerBinaryDirectory + ';' + $env:PATH }
# Buildx's Windows authentication client does not automatically use WinINET proxy
# settings. Inherit the already configured user proxy for this process only.
$internetSettings = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction SilentlyContinue
if ($internetSettings.ProxyEnable -eq 1 -and $internetSettings.ProxyServer) {
    $proxyServer = [string]$internetSettings.ProxyServer
    if ($proxyServer -notmatch '=') {
        if ($proxyServer -notmatch '^https?://') { $proxyServer = 'http://' + $proxyServer }
        if (-not $env:HTTP_PROXY) { $env:HTTP_PROXY = $proxyServer }
        if (-not $env:HTTPS_PROXY) { $env:HTTPS_PROXY = $proxyServer }
    }
}
if (-not $env:NO_PROXY) { $env:NO_PROXY = 'localhost,127.0.0.1,::1,postgres,backend,frontend' }
