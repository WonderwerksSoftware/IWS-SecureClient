#Requires -Version 5.1

$root = Split-Path -Parent $PSScriptRoot
$install = Join-Path $root "Install-IwsWebViewShellPoc.ps1"
$remove = Join-Path $root "Remove-IwsWebViewShellPoc.ps1"
if (-not (Test-Path -LiteralPath $install -PathType Leaf)) { throw "shell installer is missing" }
if (-not (Test-Path -LiteralPath $remove -PathType Leaf)) { throw "shell remover is missing" }

$installSource = Get-Content -LiteralPath $install -Raw
foreach ($required in @(
    "IwsClient.exe", "WebView2Fixed", "BUNDLE-MANIFEST.sha256",
    "Set-IwsWebViewBoundary.ps1", "IWSPrivateTransport", "IWS.lnk",
    '$shortcut.TargetPath = $installedClient',
    "Microsoft.WebView2.FixedVersionRuntime.",
    '$runtimeSource.FullName + "\*"'
)) {
    if (-not $installSource.Contains($required)) { throw "shell installer missing: $required" }
}
foreach ($prohibited in @("--app=", "msedge.exe", "netbird-ui", "setup-key")) {
    if ($installSource.Contains($prohibited)) { throw "shell installer contains prohibited behavior: $prohibited" }
}

$removeSource = Get-Content -LiteralPath $remove -Raw
foreach ($required in @("Remove-IwsWebViewBoundary.ps1", "IwsClient.exe", "WebView2Fixed", "IWS.lnk")) {
    if (-not $removeSource.Contains($required)) { throw "shell remover missing: $required" }
}
foreach ($required in @("Get-NetFirewallRule", "IWS Client Boundary POC", "Remove-NetFirewallRule")) {
    if (-not $removeSource.Contains($required)) { throw "shell remover lacks direct boundary cleanup: $required" }
}
foreach ($prohibited in @("IWSPrivateTransport", "StateRoot", "service uninstall", "RemoveIdentity")) {
    if ($removeSource.Contains($prohibited)) { throw "shell remover threatens transport identity: $prohibited" }
}

$planInstall = (& $install -BundleRoot "C:\not-used" -PlanOnly) -join "`n"
if (-not $planInstall.Contains("PLAN install dedicated IWS WebView2 shell")) { throw "installer PlanOnly is incomplete" }
$planRemove = (& $remove -PlanOnly) -join "`n"
if (-not $planRemove.Contains("PLAN preserve enrolled transport identity")) { throw "remover PlanOnly is incomplete" }

Write-Output "PASS IWS shell lifecycle contract"
