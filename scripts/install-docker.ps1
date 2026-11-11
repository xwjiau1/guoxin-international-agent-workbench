param(
    [string]$InstallerDirectory = 'D:\GuoxinAssistantRuntime\installers',
    [switch]$SkipWsl,
    [switch]$VerifyOnly
)
$ErrorActionPreference = 'Stop'

# Official manifests/releases, pinned so a changed download cannot be executed silently.
# Docker: https://github.com/microsoft/winget-pkgs/blob/master/manifests/d/Docker/DockerDesktop/4.49.0/Docker.DockerDesktop.installer.yaml
# WSL: https://github.com/microsoft/WSL/releases/tag/3.0.5
# CLI flags: https://docs.docker.com/desktop/setup/install/windows-install/
$dockerInstaller = Join-Path $InstallerDirectory 'DockerDesktop-4.49.0-Installer.exe'
$wslInstaller = Join-Path $InstallerDirectory 'wsl.3.0.5.0.x64.msi'

function Assert-Installer([string]$Path, [string]$ExpectedHash) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Installer missing: $Path" }
    $actualHash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if ($actualHash -ne $ExpectedHash) { throw "SHA256 verification failed: $Path" }
    $signature = Get-AuthenticodeSignature -LiteralPath $Path
    Write-Output "SHA256 verified: $Path; Authenticode status: $($signature.Status)"
    if ($signature.Status -eq 'NotSigned' -or $signature.Status -eq 'HashMismatch') { throw "Invalid executable signature: $Path" }
}

Assert-Installer $dockerInstaller '238BBF2AA143EC10565AAF5FCCC97BBAA4F2C7BA3631CFF0A1159A2BD2BFE939'
if (-not $SkipWsl) { Assert-Installer $wslInstaller '2313EFC5873E3F8552F42E77F950FABD913C0289078FA89D2100F3421A525EAA' }
if ($VerifyOnly) { return }

if (-not $SkipWsl) {
    Write-Output 'Installing official WSL MSI. Windows may request administrator approval. No automatic reboot is allowed.'
    $wslLog = Join-Path $InstallerDirectory 'wsl-install.log'
    $wslArguments = @('/i', ('"{0}"' -f $wslInstaller), '/qn', '/norestart', '/L*v', ('"{0}"' -f $wslLog))
    $principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
    if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        $process = Start-Process -FilePath 'msiexec.exe' -ArgumentList $wslArguments -WindowStyle Hidden -Wait -PassThru
    } else {
        $process = Start-Process -FilePath 'msiexec.exe' -ArgumentList $wslArguments -Verb RunAs -WindowStyle Hidden -Wait -PassThru
    }
    Write-Output "WSL installer exit code: $($process.ExitCode)"
    if ($process.ExitCode -eq 3010) { throw 'WSL requires a Windows restart. Restart manually, then rerun with -SkipWsl. No reboot was performed.' }
    if ($process.ExitCode -ne 0) { throw "WSL installation failed: exit $($process.ExitCode); see $wslLog" }
}

Write-Output 'Installing Docker Desktop with WSL 2 backend. Windows may request administrator approval.'
$process = Start-Process -FilePath $dockerInstaller -ArgumentList 'install', '--quiet', '--accept-license', '--backend=wsl-2' -WindowStyle Hidden -Wait -PassThru
Write-Output "Docker Desktop installer exit code: $($process.ExitCode)"
if ($process.ExitCode -eq 3010) { throw 'Docker installation requires a Windows restart. No reboot was performed.' }
if ($process.ExitCode -ne 0) { throw "Docker installation failed: exit $($process.ExitCode)" }
$desktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
if (Test-Path -LiteralPath $desktop) {
    . (Join-Path $PSScriptRoot 'docker-env.ps1')
    Start-Process -FilePath $desktop -WindowStyle Hidden
}
Write-Output 'Docker Desktop has been launched. Verify docker version and docker compose version after its engine starts.'
