#Requires -Version 5.1

[CmdletBinding()]
param([switch]$PlanOnly)

$ErrorActionPreference = "Stop"
if ($PlanOnly) {
    Write-Output "PLAN remove dedicated IWS WebView2 shell and firewall boundary"
    Write-Output "PLAN preserve enrolled transport identity"
    return
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "IWS shell removal requires Administrator approval."
}
$clientRoot = "C:\Program Files\IWS\Client"
Get-Process -Name "IwsClient", "IwsBoundaryProbe" -ErrorAction SilentlyContinue | Stop-Process -Force
$removeBoundary = Join-Path $clientRoot "Remove-IwsWebViewBoundary.ps1"
$boundaryRules = @(Get-NetFirewallRule -Group "IWS Client Boundary POC" -ErrorAction SilentlyContinue)
if ($boundaryRules.Count -gt 0) {
    $boundaryRules | Remove-NetFirewallRule -ErrorAction Stop
}
$shortcut = Join-Path ([Environment]::GetFolderPath("CommonPrograms")) "IWS.lnk"
Remove-Item -LiteralPath $shortcut -Force -ErrorAction SilentlyContinue
foreach ($path in @(
    (Join-Path $clientRoot "IwsClient.exe"),
    (Join-Path $clientRoot "IwsBoundaryProbe.exe"),
    (Join-Path $clientRoot "Microsoft.Web.WebView2.Core.dll"),
    (Join-Path $clientRoot "Microsoft.Web.WebView2.WinForms.dll"),
    (Join-Path $clientRoot "WebView2Loader.dll"),
    (Join-Path $clientRoot "IwsWebViewFirewall.psm1"),
    (Join-Path $clientRoot "Set-IwsWebViewBoundary.ps1"),
    (Join-Path $clientRoot "Remove-IwsWebViewBoundary.ps1"),
    (Join-Path $clientRoot "WebView2Fixed")
)) {
    Remove-Item -LiteralPath $path -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Output "IWS_WEBVIEW_SHELL_REMOVED=yes"
