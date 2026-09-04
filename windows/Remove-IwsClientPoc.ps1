#Requires -Version 5.1

[CmdletBinding()]
param(
    [switch]$RemoveIdentity,
    [switch]$PlanOnly
)

$ErrorActionPreference = "Stop"
$pins = Import-PowerShellDataFile -LiteralPath (Join-Path $PSScriptRoot "pins.psd1")

if ($PlanOnly) {
    Write-Output "PLAN stop and remove service $($pins.ServiceName)"
    Write-Output "PLAN remove IWS runtime and launcher"
    Write-Output "PLAN RemoveIdentity=$([bool]$RemoveIdentity)"
    return
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "IWS client removal requires Administrator approval."
}

$service = Get-Service -Name $pins.ServiceName -ErrorAction SilentlyContinue
if ($service) {
    if ($service.Status -ne "Stopped") {
        Stop-Service -Name $pins.ServiceName -Force
        $service.WaitForStatus("Stopped", [TimeSpan]::FromSeconds(20))
    }
    $transport = Join-Path $pins.InstallRoot "iws-transport.exe"
    if (Test-Path -LiteralPath $transport -PathType Leaf) {
        $null = & $transport --service $pins.ServiceName `
            --daemon-addr $pins.DaemonAddress service uninstall 2>&1
    }
    if (Get-Service -Name $pins.ServiceName -ErrorAction SilentlyContinue) {
        $null = & sc.exe delete $pins.ServiceName 2>&1
    }
}

$shortcutPath = Join-Path ([Environment]::GetFolderPath("CommonPrograms")) "IWS.lnk"
Remove-Item -LiteralPath $shortcutPath -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $pins.InstallRoot -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $pins.ClientRoot -Recurse -Force -ErrorAction SilentlyContinue
if ($RemoveIdentity) {
    Remove-Item -LiteralPath $pins.StateRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Output "IWS client POC removed."
