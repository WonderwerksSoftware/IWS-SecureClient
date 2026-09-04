#Requires -Version 5.1

$ErrorActionPreference = "Stop"
$webViewRoot = Split-Path -Parent $PSScriptRoot
$modulePath = Join-Path $webViewRoot "IwsWebViewFirewall.psm1"
$setPath = Join-Path $webViewRoot "Set-IwsWebViewBoundary.ps1"
$removePath = Join-Path $webViewRoot "Remove-IwsWebViewBoundary.ps1"
if (-not (Test-Path -LiteralPath $modulePath -PathType Leaf)) {
    throw "IwsWebViewFirewall.psm1 is missing"
}
if (-not (Test-Path -LiteralPath $setPath -PathType Leaf)) {
    throw "Set-IwsWebViewBoundary.ps1 is missing"
}
if (-not (Test-Path -LiteralPath $removePath -PathType Leaf)) {
    throw "Remove-IwsWebViewBoundary.ps1 is missing"
}

Import-Module $modulePath -Force
$programs = @(
    "C:\Program Files\IWS\Client\IwsBoundaryProbe.exe",
    "C:\Program Files\IWS\Client\IwsClient.exe",
    "C:\Program Files\IWS\Client\WebView2Fixed\msedgewebview2.exe"
)
$specs = @(Get-IwsBoundaryRuleSpecs -ProgramPaths $programs)
if ($specs.Count -ne 39) {
    throw "unexpected firewall rule count: $($specs.Count)"
}
if (-not (Test-IwsBoundaryRuleSpecs -RuleSpecs $specs)) {
    throw "valid disjoint boundary was rejected"
}

foreach ($program in $programs) {
    $programSpecs = @($specs | Where-Object Program -eq $program)
    $allow = @($programSpecs | Where-Object Action -eq "Allow")
    if ($allow.Count -ne 1 -or $allow[0].Protocol -ne "TCP" -or
        $allow[0].RemoteAddress -ne "100.83.246.85" -or
        @($allow[0].RemotePort) -notcontains "443") {
        throw "approved tuple is not exact"
    }
    $addresses = @($programSpecs.RemoteAddress | Select-Object -Unique)
    foreach ($required in @(
        "0.0.0.0-100.83.246.84",
        "100.83.246.85",
        "100.83.246.86-255.255.255.255",
        "0:0:0:0:0:0:0:0-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"
    )) {
        if ($addresses -notcontains $required) {
            throw "missing address partition: $required"
        }
    }
}

foreach ($spec in $specs) {
    if ($spec.RemoteAddress -in @("Any", "0.0.0.0/0", "::/0") -or
        ($spec.Action -eq "Block" -and $spec.Protocol -eq "Any")) {
        throw "wildcard or overlapping block entered rule set"
    }
}

$overlap = @($specs) + [pscustomobject]@{
    Name = "deliberate-overlap"
    Program = $programs[0]
    Action = "Block"
    Protocol = "TCP"
    RemoteAddress = "100.83.246.85"
    RemotePort = @("443")
}
$caught = $false
try {
    Test-IwsBoundaryRuleSpecs -RuleSpecs $overlap | Out-Null
}
catch {
    $caught = $true
}
if (-not $caught) {
    throw "overlapping approved tuple was accepted"
}

$setSource = Get-Content -LiteralPath $setPath -Raw
foreach ($required in @(
    'ErrorAction = "Stop"',
    'Failed IWS boundary rule'
)) {
    if (-not $setSource.Contains($required)) {
        throw "live rule creation is not fail-fast and attributable: $required"
    }
}

Write-Output "PASS disjoint Windows Firewall contract"
