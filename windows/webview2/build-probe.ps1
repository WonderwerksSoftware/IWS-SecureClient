#Requires -Version 5.1

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SdkPackage,
    [Parameter(Mandatory = $true)][string]$RuntimeCab,
    [string]$OutputRoot = (Join-Path $PSScriptRoot ".out")
)

$ErrorActionPreference = "Stop"
$pinsPath = Join-Path $PSScriptRoot "pins.env"
$probeSource = Join-Path $PSScriptRoot "IwsBoundaryProbe.cs"
$clientSource = Join-Path $PSScriptRoot "IwsClient.cs"
$iconPath = Join-Path $PSScriptRoot "assets\iws.ico"
$markFullPath = Join-Path $PSScriptRoot "..\..\branding\iws-mark-full.png"
$markSmallPath = Join-Path $PSScriptRoot "..\..\branding\iws-mark-small.png"
$pins = @{}
Get-Content -LiteralPath $pinsPath | ForEach-Object {
    if ($_ -match '^([A-Z0-9_]+)=(.+)$') {
        $pins[$matches[1]] = $matches[2]
    }
}

function Assert-PinnedFile {
    param([string]$Path, [string]$ExpectedSha256)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Pinned WebView2 input is missing."
    }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $ExpectedSha256) {
        throw "Pinned WebView2 input hash mismatch."
    }
}

Assert-PinnedFile -Path $SdkPackage -ExpectedSha256 $pins.WEBVIEW2_SDK_SHA256
Assert-PinnedFile -Path $RuntimeCab -ExpectedSha256 $pins.WEBVIEW2_RUNTIME_SHA256
if (-not (Test-Path -LiteralPath $probeSource -PathType Leaf)) {
    throw "IwsBoundaryProbe.cs is missing."
}
if (-not (Test-Path -LiteralPath $clientSource -PathType Leaf)) {
    throw "IwsClient.cs is missing."
}
foreach ($icon in @($iconPath)) {
    if (-not (Test-Path -LiteralPath $icon -PathType Leaf)) {
        throw "IWS application icon is missing."
    }
}
foreach ($mark in @($markFullPath, $markSmallPath)) {
    if (-not (Test-Path -LiteralPath $mark -PathType Leaf)) {
        throw "IWS application mark is missing."
    }
}

if (Test-Path -LiteralPath $OutputRoot) {
    Remove-Item -LiteralPath $OutputRoot -Recurse -Force
}
$sdkRoot = Join-Path $OutputRoot "sdk"
$bundleRoot = Join-Path $OutputRoot "probe"
$runtimeRoot = Join-Path $bundleRoot "WebView2Fixed"
New-Item -ItemType Directory -Path $sdkRoot, $bundleRoot, $runtimeRoot -Force | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::ExtractToDirectory($SdkPackage, $sdkRoot)
$null = & "$env:SystemRoot\System32\expand.exe" -F:* $RuntimeCab $runtimeRoot 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Fixed Version WebView2 Runtime extraction failed."
}

$coreDll = Join-Path $sdkRoot "lib\net462\Microsoft.Web.WebView2.Core.dll"
$winFormsDll = Join-Path $sdkRoot "lib\net462\Microsoft.Web.WebView2.WinForms.dll"
$loaderDll = Join-Path $sdkRoot "runtimes\win-x64\native\WebView2Loader.dll"
foreach ($file in @($coreDll, $winFormsDll, $loaderDll)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Pinned WebView2 SDK payload is incomplete."
    }
    Copy-Item -LiteralPath $file -Destination $bundleRoot -Force
}
Copy-Item -LiteralPath (Join-Path $sdkRoot "LICENSE.txt") -Destination $bundleRoot -Force

$probeExe = Join-Path $bundleRoot "IwsBoundaryProbe.exe"
$csc = "$env:SystemRoot\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
$compilerArguments = @(
    "/nologo", "/target:winexe", "/platform:x64", "/optimize+",
    "/reference:System.dll", "/reference:System.Core.dll",
    "/reference:System.Drawing.dll", "/reference:System.Windows.Forms.dll",
    "/reference:$coreDll", "/reference:$winFormsDll",
    "/out:$probeExe", $probeSource
)
$compilerOutput = & $csc @compilerArguments 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    throw "WebView2 probe compilation failed.`n$compilerOutput"
}

$clientExe = Join-Path $bundleRoot "IwsClient.exe"
$clientArguments = @(
    "/nologo", "/target:winexe", "/platform:x64", "/optimize+",
    "/reference:System.dll", "/reference:System.Core.dll",
    "/reference:System.Drawing.dll", "/reference:System.Windows.Forms.dll",
    "/reference:$coreDll", "/reference:$winFormsDll",
    "/win32icon:$iconPath",
    "/resource:$iconPath,IwsIcon.ico",
    "/resource:$markFullPath,IwsMarkFull.png",
    "/resource:$markSmallPath,IwsMarkSmall.png",
    "/out:$clientExe", $clientSource
)
$clientOutput = & $csc @clientArguments 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    throw "IwsClient.exe compilation failed.`n$clientOutput"
}

foreach ($supportFile in @(
    "IwsWebViewFirewall.psm1",
    "Set-IwsWebViewBoundary.ps1",
    "Remove-IwsWebViewBoundary.ps1"
)) {
    $supportPath = Join-Path $PSScriptRoot $supportFile
    if (-not (Test-Path -LiteralPath $supportPath -PathType Leaf)) {
        throw "IWS WebView2 isolation support is incomplete."
    }
    Copy-Item -LiteralPath $supportPath -Destination $bundleRoot -Force
}

$forbidden = Get-ChildItem -LiteralPath $bundleRoot -Recurse -File |
    Where-Object {
        ($_.FullName -notlike "$runtimeRoot\*") -and
        ($_.Name -match '^(?i:netbird-ui[.]exe|netbird[.]exe|msedge[.]exe|one-use[.]key)$')
    }
if ($forbidden) {
    throw "The WebView2 probe output contains a prohibited executable or credential."
}

$manifestPath = Join-Path $bundleRoot "BUNDLE-MANIFEST.sha256"
Get-ChildItem -LiteralPath $bundleRoot -Recurse -File |
    Where-Object { $_.FullName -ne $manifestPath } |
    Sort-Object FullName |
    ForEach-Object {
        $relative = $_.FullName.Substring($bundleRoot.Length + 1).Replace('\', '/')
        "{0}  {1}" -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $relative
    } | Set-Content -LiteralPath $manifestPath -Encoding ASCII

Write-Output ("PROBE_ROOT=" + $bundleRoot)
Write-Output ("PROBE_SHA256=" + (Get-FileHash -LiteralPath $probeExe -Algorithm SHA256).Hash.ToLowerInvariant())
Write-Output ("CLIENT_SHA256=" + (Get-FileHash -LiteralPath $clientExe -Algorithm SHA256).Hash.ToLowerInvariant())
