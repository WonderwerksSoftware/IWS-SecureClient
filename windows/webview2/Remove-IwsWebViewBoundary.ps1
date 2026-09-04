#Requires -Version 5.1

[CmdletBinding()]
param([switch]$PlanOnly)

$ErrorActionPreference = "Stop"
$group = "IWS Client Boundary POC"
if ($PlanOnly) {
    Write-Output "PLAN remove only firewall group: IWS Client Boundary POC"
    return
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "IWS boundary removal requires Administrator approval."
}
Get-NetFirewallRule -Group $group -ErrorAction SilentlyContinue |
    Remove-NetFirewallRule -ErrorAction Stop
Write-Output "IWS_BOUNDARY_REMOVED=yes"
