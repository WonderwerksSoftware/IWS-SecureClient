#Requires -Version 5.1

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BundleRoot,
    [switch]$PlanOnly
)

$ErrorActionPreference = "Stop"
if ($PlanOnly) {
    Write-Output "PLAN verify shell manifest and Fixed Version runtime"
    Write-Output "PLAN preserve and verify IWSPrivateTransport"
    Write-Output "PLAN install dedicated IWS WebView2 shell"
    Write-Output "PLAN install disjoint OS firewall boundary"
    Write-Output "PLAN create one IWS Start Menu shortcut"
    return
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "IWS shell installation requires Administrator approval."
}

$bundle = [IO.Path]::GetFullPath($BundleRoot).TrimEnd('\')
$manifest = Join-Path $bundle "SHELL-MANIFEST.sha256"
if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) {
    throw "IWS shell manifest is missing."
}
foreach ($line in Get-Content -LiteralPath $manifest) {
    if ($line -notmatch '^([0-9a-f]{64})  (.+)$') { throw "IWS shell manifest is malformed." }
    $path = [IO.Path]::GetFullPath((Join-Path $bundle $matches[2]))
    if (-not $path.StartsWith($bundle + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "IWS shell manifest path escaped its bundle."
    }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or
        (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $matches[1]) {
        throw "IWS shell manifest verification failed."
    }
}

$service = Get-Service -Name "IWSPrivateTransport" -ErrorAction Stop
if ($service.Status -ne "Running") { throw "IWS private connectivity is not running." }
$transport = "C:\Program Files\IWS\Transport\iws-transport.exe"
$null = & $transport --daemon-addr npipe://iws-private-transport status --check startup 2>&1
if ($LASTEXITCODE -ne 0) { throw "IWS private connectivity is not ready." }

$clientRoot = "C:\Program Files\IWS\Client"
$installedClient = Join-Path $clientRoot "IwsClient.exe"
$installedProbe = Join-Path $clientRoot "IwsBoundaryProbe.exe"
$installedRuntime = Join-Path $clientRoot "WebView2Fixed"
Get-Process -Name "IwsClient", "IwsBoundaryProbe" -ErrorAction SilentlyContinue | Stop-Process -Force
New-Item -ItemType Directory -Path $clientRoot -Force | Out-Null
if (Test-Path -LiteralPath $installedRuntime) { Remove-Item $installedRuntime -Recurse -Force }
foreach ($file in @(
    "IwsClient.exe", "IwsBoundaryProbe.exe", "Microsoft.Web.WebView2.Core.dll",
    "Microsoft.Web.WebView2.WinForms.dll", "WebView2Loader.dll",
    "IwsWebViewFirewall.psm1", "Set-IwsWebViewBoundary.ps1", "Remove-IwsWebViewBoundary.ps1"
)) {
    Copy-Item -LiteralPath (Join-Path $bundle $file) -Destination $clientRoot -Force
}
$runtimeSource = Get-ChildItem -LiteralPath (Join-Path $bundle "WebView2Fixed") `
    -Directory -Filter "Microsoft.WebView2.FixedVersionRuntime.*" |
    Select-Object -First 1
if (-not $runtimeSource) { throw "Pinned Fixed Version WebView2 Runtime is missing." }
New-Item -ItemType Directory -Path $installedRuntime -Force | Out-Null
Copy-Item -Path ($runtimeSource.FullName + "\*") -Destination $installedRuntime -Recurse -Force

$removeBoundary = Join-Path $clientRoot "Remove-IwsWebViewBoundary.ps1"
$setBoundary = Join-Path $clientRoot "Set-IwsWebViewBoundary.ps1"
& $removeBoundary
& $setBoundary -ProgramPaths @(
    $installedClient,
    $installedProbe,
    (Join-Path $installedRuntime "msedgewebview2.exe")
)

$shortcutPath = Join-Path ([Environment]::GetFolderPath("CommonPrograms")) "IWS.lnk"
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $installedClient
$shortcut.WorkingDirectory = $clientRoot
$shortcut.IconLocation = $installedClient + ",0"
$shortcut.Save()
Write-Output "IWS_WEBVIEW_SHELL_INSTALLED=yes"
