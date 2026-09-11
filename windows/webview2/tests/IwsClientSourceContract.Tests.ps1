#Requires -Version 5.1

$ErrorActionPreference = "Stop"
$webViewRoot = Split-Path -Parent $PSScriptRoot
$sourcePath = Join-Path $webViewRoot "IwsClient.cs"
if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
    throw "IwsClient.cs is missing"
}
$source = Get-Content -LiteralPath $sourcePath -Raw

foreach ($required in @(
    "https://portal.iws.internal/",
    "CoreWebView2Environment.CreateAsync",
    "SpecialFolder.LocalApplicationData",
    '"IWS", "WebView2"',
    "IWSPrivateTransport",
    "status --check startup",
    "CanGoBack",
    "GoBack()",
    "NavigationStarting +=",
    "AddWebResourceRequestedFilter",
    "WebResourceRequested +=",
    "CreateWebResourceResponse",
    "NewWindowRequested +=",
    "DownloadStarting +=",
    "PermissionRequested +=",
    "LaunchingExternalUriScheme +=",
    "AreDevToolsEnabled = false",
    "AreDefaultContextMenusEnabled = false",
    "IsPasswordAutosaveEnabled = false",
    "IsGeneralAutofillEnabled = false",
    "GetDevToolsProtocolEventReceiver",
    "Network.webSocketCreated",
    "Network.responseReceived",
    "api-response-200",
    "CallDevToolsProtocolMethodAsync",
    "Network.enable",
    "shell-evidence.jsonl"
)) {
    if (-not $source.Contains($required)) {
        throw "IWS client source contract missing: $required"
    }
}
foreach ($prohibited in @(
    "ProbeNativeIsolationAsync",
    "iws_poc_cookie",
    "fetch('/api/health'",
    "__iwsTlsSocketProof",
    "--app=",
    "netbird-ui",
    "netbird.exe",
    "/build/",
    "/inventory/",
    "https://impactwiringsolutions.com"
)) {
    if ($source.Contains($prohibited)) {
        throw "IWS client source contains prohibited behavior: $prohibited"
    }
}

Write-Output "PASS IWS WebView2 shell source contract"
