#Requires -Version 5.1

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string[]]$ProgramPaths,
    [switch]$PlanOnly
)

$ErrorActionPreference = "Stop"
$group = "IWS Client Boundary POC"
Import-Module (Join-Path $PSScriptRoot "IwsWebViewFirewall.psm1") -Force

$normalizedPrograms = @($ProgramPaths | ForEach-Object { [IO.Path]::GetFullPath($_) } | Select-Object -Unique)
$specs = @(Get-IwsBoundaryRuleSpecs -ProgramPaths $normalizedPrograms)
$null = Test-IwsBoundaryRuleSpecs -RuleSpecs $specs
if ($PlanOnly) {
    $specs | ConvertTo-Json -Depth 4
    return
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "IWS boundary installation requires Administrator approval."
}
foreach ($program in $normalizedPrograms) {
    if (-not (Test-Path -LiteralPath $program -PathType Leaf)) {
        throw "An IWS boundary program is missing."
    }
    if ($program -notlike "C:\Program Files\IWS\Client\*") {
        throw "IWS boundary programs must use the dedicated IWS installation path."
    }
    if ($program -match '(?i)\\Microsoft\\Edge\\Application\\|\\EdgeWebView\\Application\\') {
        throw "Shared Edge or Evergreen WebView2 executables are prohibited."
    }
}
if (Get-NetFirewallRule -Group $group -ErrorAction SilentlyContinue) {
    throw "The IWS boundary rule group already exists."
}

try {
    foreach ($spec in $specs) {
        $parameters = @{
            DisplayName = "IWS Boundary - " + $spec.Name
            Group = $group
            Direction = "Outbound"
            Action = $spec.Action
            Enabled = "True"
            Profile = "Any"
            Program = $spec.Program
            Protocol = $spec.Protocol
            RemoteAddress = $spec.RemoteAddress
            ErrorAction = "Stop"
        }
        if ($spec.Protocol -in @("TCP", "UDP")) {
            $parameters.RemotePort = @($spec.RemotePort)
        }
        try {
            New-NetFirewallRule @parameters | Out-Null
        }
        catch {
            throw "Failed IWS boundary rule '$($spec.Name)': $($_.Exception.Message)"
        }
    }

    $installed = @(Get-NetFirewallRule -Group $group -ErrorAction Stop)
    if ($installed.Count -ne $specs.Count) {
        throw "IWS boundary firewall readback count mismatch."
    }
    Write-Output ("IWS_BOUNDARY_RULE_COUNT=" + $installed.Count)
}
catch {
    Get-NetFirewallRule -Group $group -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule -ErrorAction SilentlyContinue
    throw
}
