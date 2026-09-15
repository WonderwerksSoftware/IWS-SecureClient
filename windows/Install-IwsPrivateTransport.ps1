#Requires -Version 5.1

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$PayloadPath,
    [Parameter(Mandatory = $true)][string]$BundleRoot,
    [Parameter(Mandatory = $true)][ValidateSet("Enroll", "Repair")][string]$Mode,
    [switch]$PreserveSetupKey,
    [string]$CleanRecoveryRoot,
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

function Set-IwsRestrictedFileAcl {
    param([Parameter(Mandatory = $true)][string]$Path)
    $null = & icacls.exe $Path /inheritance:r /grant:r `
        "*S-1-5-18:F" "*S-1-5-32-544:F" 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to restrict IWS receipt permissions."
    }
}

function Save-IwsInstallationReceipt {
    param([Parameter(Mandatory = $true)][psobject]$Payload)
    $receiptRoot = "C:\ProgramData\IWS\Install"
    Set-IwsRestrictedDirectoryAcl -Path $receiptRoot
    $receiptPath = Join-Path $receiptRoot "receipt.json"
    [pscustomobject]@{
        schemaVersion = 1
        deviceId = [string]$Payload.deviceId
        generation = [int]$Payload.generation
        clientCheckpoint = [string]$Payload.clientCheckpoint
    } | ConvertTo-Json | Set-Content -LiteralPath $receiptPath -Encoding UTF8
    Set-IwsRestrictedFileAcl -Path $receiptPath
}

function Get-IwsEnrollmentEvidencePath {
    param([Parameter(Mandatory = $true)][psobject]$Payload)
    return Join-Path "C:\ProgramData\IWS\EnrollmentEvidence" `
        (([string]$Payload.deviceId) + "-g" + ([int]$Payload.generation) + ".json")
}

function Save-IwsEnrollmentEvidence {
    param(
        [Parameter(Mandatory = $true)][psobject]$Payload,
        [Parameter(Mandatory = $true)][ValidateSet("ATTEMPTED", "ESTABLISHED", "REJECTED")][string]$Status
    )
    $root = "C:\ProgramData\IWS\EnrollmentEvidence"
    Set-IwsRestrictedDirectoryAcl -Path $root
    $path = Get-IwsEnrollmentEvidencePath -Payload $Payload
    [pscustomobject]@{
        schemaVersion = 1
        status = $Status
        deviceId = [string]$Payload.deviceId
        generation = [int]$Payload.generation
        clientCheckpoint = [string]$Payload.clientCheckpoint
    } | ConvertTo-Json | Set-Content -LiteralPath $path -Encoding UTF8
    Set-IwsRestrictedFileAcl -Path $path
}

try {
    $payload = Read-IwsPayload -Path $PayloadPath
    $setupKeyPath = $payload.setup_key_file
    if (-not $PlanOnly -and -not $PreserveSetupKey) {
        $removeSetupKeyOnExit = $true
    }
    if ($Mode -eq "Enroll" -and $PreserveSetupKey) {
        throw "Enrollment cannot retain temporary enrollment material."
    }
    if ($Mode -eq "Repair" -and -not [string]::IsNullOrWhiteSpace($CleanRecoveryRoot)) {
        throw "Repair cannot establish a clean enrollment record."
    }

    if ($PlanOnly) {
        Write-Output "PLAN verify signed pinned IWS transport artifacts"
        Write-Output "PLAN install IWS private transport service or resume owned service"
        if ($Mode -eq "Enroll") {
            Write-Output "PLAN enroll IWS device from protected one-use file"
            Write-Output "PLAN save protected non-secret installation receipt"
        }
        else {
            Write-Output "PLAN preserve enrolled IWS identity without using setup key"
        }
        Write-Output "PLAN lock transport settings after enrollment"
        return
    }

    if ($env:OS -ne "Windows_NT") {
        throw "Live IWS client installation requires Windows."
    }
    if (-not (Test-IwsAdministrator)) {
        throw "IWS client installation requires Administrator approval."
    }
    Write-Output "IWS_SETUP_PHASE=TRANSPORT_INSTALLATION"
    if ($Mode -eq "Enroll" -and -not (Test-Path -LiteralPath $setupKeyPath -PathType Leaf)) {
        throw "Protected one-use enrollment material is missing."
    }
    if ($Mode -eq "Enroll" -and $payload.expiresAt -le [DateTime]::UtcNow) {
        throw "IWS_ENROLLMENT_CREDENTIAL_REJECTED"
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
    Set-IwsRestrictedDirectoryAcl -Path $pins.StateRoot
    New-Item -ItemType Directory -Path $pins.ClientRoot -Force | Out-Null

    $installedTransport = Join-Path $pins.InstallRoot "iws-transport.exe"
    $installedWintun = Join-Path $pins.InstallRoot "wintun.dll"
    $service = Get-Service -Name $pins.ServiceName -ErrorAction SilentlyContinue
    if ($service) {
        $serviceConfig = Get-CimInstance -ClassName Win32_Service `
            -Filter ("Name='" + $pins.ServiceName + "'") -ErrorAction Stop
        if (-not $serviceConfig -or -not (Test-IwsServiceCommandPathOwned `
            -CommandLine ([string]$serviceConfig.PathName) -ExpectedPath $installedTransport)) {
            throw "The existing service with the IWS name is not owned by IWS."
        }
        Assert-IwsArtifact -Path $installedTransport -ExpectedSha256 $pins.TransportSha256
        Assert-IwsArtifact -Path $installedWintun -ExpectedSha256 $pins.WintunSha256
        if ((Get-AuthenticodeSignature -LiteralPath $installedTransport).Status -ne
            [System.Management.Automation.SignatureStatus]::Valid) {
            throw "Existing IWS transport publisher verification failed."
        }
    }
    else {
        New-Item -ItemType Directory -Path $pins.InstallRoot -Force | Out-Null
        Copy-Item -LiteralPath $sourceTransport -Destination $installedTransport -Force
        Copy-Item -LiteralPath $sourceWintun -Destination $installedWintun -Force
        Assert-IwsArtifact -Path $installedTransport -ExpectedSha256 $pins.TransportSha256
        Assert-IwsArtifact -Path $installedWintun -ExpectedSha256 $pins.WintunSha256
        if ((Get-AuthenticodeSignature -LiteralPath $installedTransport).Status -ne
            [System.Management.Automation.SignatureStatus]::Valid) {
            throw "Installed IWS transport publisher verification failed."
        }
    }
    Copy-Item -LiteralPath (Join-Path $BundleRoot "IwsPrivateTransport.psm1") `
        -Destination (Join-Path $pins.ClientRoot "IwsPrivateTransport.psm1") -Force
    Copy-Item -LiteralPath (Join-Path $BundleRoot "pins.psd1") `
        -Destination (Join-Path $pins.ClientRoot "pins.psd1") -Force
    $env:NB_STATE_DIR = $pins.StateRoot
    if (-not $service) {
        $serviceArgs = Get-IwsServiceInstallArguments -StateDir $pins.StateRoot
        Invoke-IwsNativeSanitized -FilePath $installedTransport -Arguments $serviceArgs `
            -FailureMessage "IWS private transport service installation failed."
    }
    Set-Service -Name $pins.ServiceName -DisplayName $pins.ServiceDisplayName -StartupType Automatic
    $null = & sc.exe description $pins.ServiceName "Private connectivity for IWS." 2>&1
    $service = Get-Service -Name $pins.ServiceName -ErrorAction Stop
    if ($service.Status -ne "Running") { Start-Service -Name $pins.ServiceName }
    (Get-Service -Name $pins.ServiceName).WaitForStatus("Running", [TimeSpan]::FromSeconds(20))

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $statusOutput = & $installedTransport --daemon-addr $pins.DaemonAddress status 2>&1 | Out-String
        $statusExit = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $previousPreference }
    $nativeIdentityStatus = Get-IwsNativeIdentityStatusFromOutput -Output $statusOutput -ExitCode $statusExit

    if ($Mode -eq "Enroll") {
        $preflightFailureMarker = Get-IwsEnrollmentPreflightFailureMarker `
            -NativeIdentityStatus $nativeIdentityStatus
        if (-not [string]::IsNullOrEmpty($preflightFailureMarker)) {
            Write-Output $preflightFailureMarker
            throw "IWS_ENROLLMENT_TRANSIENT"
        }
        Write-Output "IWS_SETUP_PHASE=ENROLLMENT"
        $enrollmentArgs = Get-IwsEnrollmentArguments -Payload $payload
        Save-IwsEnrollmentEvidence -Payload $payload -Status "ATTEMPTED"
        try {
            Invoke-IwsNativeSanitized -FilePath $installedTransport -Arguments $enrollmentArgs `
                -FailureMessage "IWS device provisioning failed." -ClassifyEnrollmentFailure
        }
        catch {
            if ($_.Exception.Message -eq "IWS_ENROLLMENT_CREDENTIAL_REJECTED") {
                Write-Output "IWS_SETUP_ERROR=ENROLLMENT_CREDENTIAL_REJECTED"
                Save-IwsEnrollmentEvidence -Payload $payload -Status "REJECTED"
            }
            elseif ($_.Exception.Message -eq "IWS_ENROLLMENT_TRANSIENT") {
                Write-Output "IWS_SETUP_ERROR=ENROLLMENT_TRANSIENT"
                Remove-Item -LiteralPath (Get-IwsEnrollmentEvidencePath -Payload $payload) -Force -ErrorAction Stop
            }
            throw
        }
        Write-Output "IWS_SETUP_PHASE=ENROLLMENT_ESTABLISHED"
        Save-IwsEnrollmentEvidence -Payload $payload -Status "ESTABLISHED"
        if (-not [string]::IsNullOrWhiteSpace($CleanRecoveryRoot)) {
            Import-Module (Join-Path $BundleRoot "IwsCleanTransaction.psm1") -Force
            Write-IwsCleanEnrollmentEstablished -RecoveryRoot $CleanRecoveryRoot `
                -DeviceId $payload.deviceId -Generation $payload.generation `
                -ClientCheckpoint $payload.clientCheckpoint
        }
        Save-IwsInstallationReceipt -Payload $payload
    }

    Write-Output "IWS_SETUP_PHASE=TRANSPORT_READY"
    if ($Mode -eq "Enroll" -or $nativeIdentityStatus -ne "NeedsLogin") {
        $lockArgs = Get-IwsServiceLockArguments -StateDir $pins.StateRoot
        Invoke-IwsNativeSanitized -FilePath $installedTransport -Arguments $lockArgs `
            -FailureMessage "IWS transport settings lock failed."
    }
    Set-Service -Name $pins.ServiceName -DisplayName $pins.ServiceDisplayName -StartupType Automatic
    $null = & sc.exe description $pins.ServiceName "Private connectivity for IWS." 2>&1

    if ($Mode -eq "Enroll") {
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
