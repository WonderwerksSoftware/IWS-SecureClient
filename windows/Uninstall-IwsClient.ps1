#Requires -Version 5.1

[CmdletBinding()]
param([switch]$PlanOnly)

$ErrorActionPreference = "Stop"
$pins = Import-PowerShellDataFile -LiteralPath (Join-Path $PSScriptRoot "pins.psd1")
Import-Module (Join-Path $PSScriptRoot "IwsPrivateTransport.psm1") -Force
$uninstallKey = "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\IWS Secure Client"

if ($PlanOnly) {
    Write-Output "PLAN remove service IWSPrivateTransport only when its command path is IWS-owned"
    Write-Output "PLAN remove firewall group IWS Client Boundary POC"
    Write-Output "PLAN remove IWS-owned certificate and exact-host NRPT state"
    Write-Output "PLAN remove C:\Program Files\IWS and C:\ProgramData\IWS"
    Write-Output "PLAN preserve unrelated VPN services, rules, certificates, and files"
    return
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "IWS uninstall requires ordinary Administrator approval."
}

$installedTransport = Join-Path $pins.InstallRoot "iws-transport.exe"
$service = Get-Service -Name $pins.ServiceName -ErrorAction SilentlyContinue
if ($service) {
    $serviceConfig = Get-CimInstance -ClassName Win32_Service `
        -Filter ("Name='" + $pins.ServiceName + "'") -ErrorAction Stop
    if (-not $serviceConfig -or -not (Test-IwsServiceCommandPathOwned `
        -CommandLine ([string]$serviceConfig.PathName) -ExpectedPath $installedTransport)) {
        throw "The service with the IWS name is not owned by IWS; uninstall refused."
    }
}

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
    if (Test-Path -LiteralPath $installedTransport -PathType Leaf) {
        $null = & $installedTransport --service $pins.ServiceName `
            --daemon-addr $pins.DaemonAddress service uninstall 2>&1
    }
    if (Get-Service -Name $pins.ServiceName -ErrorAction SilentlyContinue) {
        $null = & sc.exe delete $pins.ServiceName 2>&1
    }
}

$shortcut = Join-Path ([Environment]::GetFolderPath("CommonPrograms")) "IWS.lnk"
Remove-Item -LiteralPath $shortcut -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $uninstallKey -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $pins.ClientRoot -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $pins.InstallRoot -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath "C:\ProgramData\IWS" -Recurse -Force -ErrorAction SilentlyContinue

Write-Output "IWS_UNINSTALL_COMPLETE=yes"
