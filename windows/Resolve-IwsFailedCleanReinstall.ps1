#Requires -Version 5.1

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BundleRoot,
    [Parameter(Mandatory = $true)][string]$RecoveryRoot,
    [Parameter(Mandatory = $true)][string]$DeviceId,
    [Parameter(Mandatory = $true)][int]$Generation
)

$ErrorActionPreference = "Stop"
Import-Module (Join-Path $BundleRoot "IwsCleanTransaction.psm1") -Force
$receipt = "C:\ProgramData\IWS\Install\receipt.json"
$disposition = Get-IwsCleanFailureDisposition -RecoveryRoot $RecoveryRoot `
    -ReceiptPath $receipt -DeviceId $DeviceId -Generation $Generation
if ($disposition -eq "PreserveNewEnrollment") {
    Write-Output "IWS_CLEAN_FAILURE=NEW_ENROLLMENT_PRESERVED"
    return
}
& (Join-Path $BundleRoot "Restore-IwsClientAfterFailedClean.ps1") `
    -BundleRoot $BundleRoot -RecoveryRoot $RecoveryRoot
