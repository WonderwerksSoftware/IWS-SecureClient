#Requires -Version 5.1

$ErrorActionPreference = "Stop"
$webViewRoot = Split-Path -Parent $PSScriptRoot
$sourcePath = Join-Path $webViewRoot "IwsBoundaryProbe.cs"
if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
    throw "IwsBoundaryProbe.cs is missing"
}

$source = Get-Content -LiteralPath $sourcePath -Raw
foreach ($required in @(
    "CoreWebView2Environment.CreateAsync",
    "EnsureCoreWebView2Async",
    "BrowserProcessId",
    "NavigateToString",
    "window.chrome.webview.postMessage",
    "initialization-error",
    "http://100.83.246.85:443/",
    "http://100.116.25.100:443/",
    "http://100.99.71.15:443/",
    "http://100.127.228.103:443/",
    "http://10.1.10.1:443/",
    "http://172.16.0.1:443/",
    "http://192.168.50.1:443/",
    "http://100.83.50.15:443/",
    "http://1.1.1.1:443/"
)) {
    if (-not $source.Contains($required)) {
        throw "probe source contract missing: $required"
    }
}
foreach ($prohibited in @(
    "WebResourceRequested +=",
    "args.Cancel = true",
    "Microsoft Edge",
    "--app="
)) {
    if ($source.Contains($prohibited)) {
        throw "probe contains prohibited filtering or Edge behavior: $prohibited"
    }
}

Write-Output "PASS WebView2 probe source contract"
