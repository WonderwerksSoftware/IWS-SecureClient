#Requires -Version 5.1

$ErrorActionPreference = "Stop"
$webViewRoot = Split-Path -Parent $PSScriptRoot
$buildScript = Join-Path $webViewRoot "build-probe.ps1"
$pinsPath = Join-Path $webViewRoot "pins.env"

if (-not (Test-Path -LiteralPath $buildScript -PathType Leaf)) {
    throw "build-probe.ps1 is missing"
}
if (-not (Test-Path -LiteralPath $pinsPath -PathType Leaf)) {
    throw "pins.env is missing"
}

$pins = @{}
Get-Content -LiteralPath $pinsPath | ForEach-Object {
    if ($_ -match '^([A-Z0-9_]+)=(.+)$') {
        $pins[$matches[1]] = $matches[2]
    }
}

$expected = @{
    WEBVIEW2_SDK_VERSION = "1.0.4191.47"
    WEBVIEW2_SDK_SHA256 = "f492bbf547d0da329553b6727435b677579b1e9f91cc9e4a1ad029366d5f23d0"
    WEBVIEW2_RUNTIME_VERSION = "152.0.4191.53"
    WEBVIEW2_RUNTIME_SHA256 = "f8f200b57d6a7a71d380f777f5c0ea0f71f520048add21e15737121de9ba4f68"
}
foreach ($name in $expected.Keys) {
    if ($pins[$name] -ne $expected[$name]) {
        throw "pin mismatch: $name"
    }
}

$source = Get-Content -LiteralPath $buildScript -Raw
foreach ($required in @(
    "Get-FileHash",
    "Microsoft.Web.WebView2.Core.dll",
    "Microsoft.Web.WebView2.WinForms.dll",
    "WebView2Loader.dll",
    "Framework64\v4.0.30319\csc.exe",
    "IwsClient.cs",
    "IwsClient.exe",
    "IwsWebViewFirewall.psm1",
    "Set-IwsWebViewBoundary.ps1",
    "Remove-IwsWebViewBoundary.ps1",
    "BUNDLE-MANIFEST.sha256"
)) {
    if (-not $source.Contains($required)) {
        throw "build contract missing: $required"
    }
}

Write-Output "PASS WebView2 probe build contract"
