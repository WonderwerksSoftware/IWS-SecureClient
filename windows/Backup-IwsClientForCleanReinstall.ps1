#Requires -Version 5.1

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BundleRoot,
    [Parameter(Mandatory = $true)][string]$RecoveryRoot,
    [switch]$PlanOnly
)

$ErrorActionPreference = "Stop"
$pins = Import-PowerShellDataFile -LiteralPath (Join-Path $BundleRoot "pins.psd1")
Import-Module (Join-Path $BundleRoot "IwsPrivateTransport.psm1") -Force

if ($PlanOnly) {
    Write-Output "PLAN validate IWS-owned service and component paths"
    Write-Output "PLAN retire active IWS files and identity under protected local recovery storage"
    Write-Output "PLAN remove only IWS-owned trust, boundary, shortcut, and uninstall registration"
    return
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "IWS clean reinstall requires ordinary Administrator approval."
}
$recovery = [IO.Path]::GetFullPath($RecoveryRoot).TrimEnd('\')
$allowedRoot = [IO.Path]::GetFullPath("C:\ProgramData\IWS\Recovery").TrimEnd('\')
if (-not $recovery.StartsWith($allowedRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
    (Test-Path -LiteralPath $recovery)) {
    throw "IWS recovery path is invalid."
}
New-Item -ItemType Directory -Path $recovery -Force | Out-Null
$null = & icacls.exe $recovery /inheritance:r /grant:r `
    "*S-1-5-18:(OI)(CI)F" "*S-1-5-32-544:(OI)(CI)F" 2>&1
if ($LASTEXITCODE -ne 0) { throw "Unable to protect IWS recovery storage." }

$installedTransport = Join-Path $pins.InstallRoot "iws-transport.exe"
$service = Get-Service -Name $pins.ServiceName -ErrorAction SilentlyContinue
if ($service) {
    $serviceConfig = Get-CimInstance -ClassName Win32_Service `
        -Filter ("Name='" + $pins.ServiceName + "'") -ErrorAction Stop
    if (-not $serviceConfig -or -not (Test-IwsServiceCommandPathOwned `
        -CommandLine ([string]$serviceConfig.PathName) -ExpectedPath $installedTransport)) {
        throw "The service with the IWS name is not owned by IWS; clean reinstall refused."
    }
    Assert-IwsArtifact -Path $installedTransport -ExpectedSha256 $pins.TransportSha256
}

Set-Content -LiteralPath (Join-Path $recovery "rollback.ready") -Value "IWS_ROLLBACK_V1" -Encoding ASCII

$trust = Join-Path $pins.ClientRoot "Install-IwsProductionTrust.ps1"
$certificate = Join-Path $pins.ClientRoot "iws-production-root-ca.crt"
if ((Test-Path -LiteralPath $trust -PathType Leaf) -and
    (Test-Path -LiteralPath $certificate -PathType Leaf)) {
    & $trust -CertificatePath $certificate -Remove
}
$boundary = Join-Path $pins.ClientRoot "Remove-IwsWebViewBoundary.ps1"
if (Test-Path -LiteralPath $boundary -PathType Leaf) { & $boundary }
Get-Process -Name "IwsClient", "IwsBoundaryProbe" -ErrorAction SilentlyContinue |
    Stop-Process -Force -ErrorAction Stop
if ($service) {
    if ($service.Status -ne "Stopped") {
        Stop-Service -Name $pins.ServiceName
        $service.WaitForStatus("Stopped", [TimeSpan]::FromSeconds(20))
    }
    $null = & $installedTransport --service $pins.ServiceName `
        --daemon-addr $pins.DaemonAddress service uninstall 2>&1
    if (Get-Service -Name $pins.ServiceName -ErrorAction SilentlyContinue) {
        $null = & sc.exe delete $pins.ServiceName 2>&1
    }
}

$shortcut = Join-Path ([Environment]::GetFolderPath("CommonPrograms")) "IWS.lnk"
Remove-Item -LiteralPath $shortcut -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\IWS Secure Client" `
    -Recurse -Force -ErrorAction SilentlyContinue
foreach ($item in @(
    @{ Source = $pins.InstallRoot; Name = "Transport" },
    @{ Source = $pins.ClientRoot; Name = "Client" },
    @{ Source = "C:\Program Files\IWS\Installer"; Name = "Installer" },
    @{ Source = $pins.StateRoot; Name = "State" },
    @{ Source = "C:\ProgramData\IWS\Install"; Name = "Receipt" }
)) {
    if (Test-Path -LiteralPath $item.Source) {
        Move-Item -LiteralPath $item.Source -Destination (Join-Path $recovery $item.Name)
    }
}
Write-Output "IWS_SETUP_PHASE=CLEAN_BACKUP"
