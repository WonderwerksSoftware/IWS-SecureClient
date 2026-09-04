#Requires -Version 5.1

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$PayloadPath,
    [Parameter(Mandatory = $true)][string]$BundleRoot,
    [switch]$PlanOnly
)

$ErrorActionPreference = "Stop"
$modulePath = Join-Path $PSScriptRoot "IwsPrivateTransport.psm1"
$pinsPath = Join-Path $PSScriptRoot "pins.psd1"
Import-Module $modulePath -Force
$pins = Import-PowerShellDataFile -LiteralPath $pinsPath

$removeSetupKeyOnExit = $false
$setupKeyPath = $null

function Test-IwsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Set-IwsRestrictedDirectoryAcl {
    param([Parameter(Mandatory = $true)][string]$Path)
    New-Item -ItemType Directory -Path $Path -Force | Out-Null
    $null = & icacls.exe $Path /inheritance:r /grant:r `
        "*S-1-5-18:(OI)(CI)F" "*S-1-5-32-544:(OI)(CI)F" 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to restrict IWS transport state permissions."
    }
}

try {
    $payload = Read-IwsPayload -Path $PayloadPath
    $setupKeyPath = $payload.setup_key_file
    if (-not $PlanOnly) {
        $removeSetupKeyOnExit = $true
    }

    if ($PlanOnly) {
        Write-Output "PLAN verify signed pinned IWS transport artifacts"
        Write-Output "PLAN install IWS private transport service"
        Write-Output "PLAN enroll IWS device from protected one-use file"
        Write-Output "PLAN lock transport settings after enrollment"
        return
    }

    if ($env:OS -ne "Windows_NT") {
        throw "Live IWS client installation requires Windows."
    }
    if (-not (Test-IwsAdministrator)) {
        throw "IWS client installation requires Administrator approval."
    }
    if (-not (Test-Path -LiteralPath $setupKeyPath -PathType Leaf)) {
        throw "Protected one-use enrollment material is missing."
    }

    $sourceTransport = Join-Path $BundleRoot "iws-transport.exe"
    $sourceWintun = Join-Path $BundleRoot "wintun.dll"
    Assert-IwsArtifact -Path $sourceTransport -ExpectedSha256 $pins.TransportSha256
    Assert-IwsArtifact -Path $sourceWintun -ExpectedSha256 $pins.WintunSha256
    if (Get-ChildItem -LiteralPath $BundleRoot -Recurse -File |
        Where-Object { $_.Name -match "(?i)netbird-ui|tray" }) {
        throw "The IWS bundle contains a prohibited desktop transport UI."
    }
    $signature = Get-AuthenticodeSignature -LiteralPath $sourceTransport
    if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid) {
        throw "IWS transport publisher verification failed."
    }
    if (Get-Service -Name $pins.ServiceName -ErrorAction SilentlyContinue) {
        throw "The disposable IWS transport service already exists."
    }

    Set-IwsRestrictedDirectoryAcl -Path $pins.StateRoot
    New-Item -ItemType Directory -Path $pins.InstallRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $pins.ClientRoot -Force | Out-Null

    $installedTransport = Join-Path $pins.InstallRoot "iws-transport.exe"
    $installedWintun = Join-Path $pins.InstallRoot "wintun.dll"
    Copy-Item -LiteralPath $sourceTransport -Destination $installedTransport -Force
    Copy-Item -LiteralPath $sourceWintun -Destination $installedWintun -Force
    Copy-Item -LiteralPath (Join-Path $BundleRoot "IwsPrivateTransport.psm1") `
        -Destination (Join-Path $pins.ClientRoot "IwsPrivateTransport.psm1") -Force
    Copy-Item -LiteralPath (Join-Path $BundleRoot "pins.psd1") `
        -Destination (Join-Path $pins.ClientRoot "pins.psd1") -Force
    Assert-IwsArtifact -Path $installedTransport -ExpectedSha256 $pins.TransportSha256
    Assert-IwsArtifact -Path $installedWintun -ExpectedSha256 $pins.WintunSha256
    if ((Get-AuthenticodeSignature -LiteralPath $installedTransport).Status -ne
        [System.Management.Automation.SignatureStatus]::Valid) {
        throw "Installed IWS transport publisher verification failed."
    }

    $env:NB_STATE_DIR = $pins.StateRoot
    $serviceArgs = Get-IwsServiceInstallArguments -StateDir $pins.StateRoot
    Invoke-IwsNativeSanitized -FilePath $installedTransport -Arguments $serviceArgs `
        -FailureMessage "IWS private transport service installation failed."
    Set-Service -Name $pins.ServiceName -DisplayName $pins.ServiceDisplayName -StartupType Automatic
    $null = & sc.exe description $pins.ServiceName "Private connectivity for IWS." 2>&1
    Start-Service -Name $pins.ServiceName
    (Get-Service -Name $pins.ServiceName).WaitForStatus("Running", [TimeSpan]::FromSeconds(20))

    $enrollmentArgs = Get-IwsEnrollmentArguments -Payload $payload
    Invoke-IwsNativeSanitized -FilePath $installedTransport -Arguments $enrollmentArgs `
        -FailureMessage "IWS device provisioning failed."

    $lockArgs = Get-IwsServiceLockArguments -StateDir $pins.StateRoot
    Invoke-IwsNativeSanitized -FilePath $installedTransport -Arguments $lockArgs `
        -FailureMessage "IWS transport settings lock failed."
    Set-Service -Name $pins.ServiceName -DisplayName $pins.ServiceDisplayName -StartupType Automatic
    $null = & sc.exe description $pins.ServiceName "Private connectivity for IWS." 2>&1

    $connected = $false
    for ($attempt = 0; $attempt -lt 30; $attempt += 1) {
        if (Test-IwsNativeSuccess -FilePath $installedTransport -Arguments @(
            "--daemon-addr", $pins.DaemonAddress, "status", "--check", "startup"
        )) {
            $connected = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $connected) {
        throw "IWS private connectivity did not become ready."
    }

    Write-Output "IWS private connectivity is ready."
}
finally {
    if ($removeSetupKeyOnExit -and $setupKeyPath -and
        (Test-Path -LiteralPath $setupKeyPath -PathType Leaf)) {
        try {
            Remove-Item -LiteralPath $setupKeyPath -Force -ErrorAction Stop
        }
        catch {
            [Console]::Error.WriteLine("IWS client installation failed to remove temporary enrollment material.")
            throw
        }
    }
}
