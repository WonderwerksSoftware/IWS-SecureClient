#Requires -Version 5.1

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$PackagingRoot,
    [Parameter(Mandatory = $true)][string]$WindowsRoot
)

$ErrorActionPreference = "Stop"
$files = @(
    (Join-Path $PackagingRoot "Install-IwsWebViewShellDevice.ps1"),
    (Join-Path $WindowsRoot "Install-IwsPrivateTransport.ps1"),
    (Join-Path $WindowsRoot "Uninstall-IwsClient.ps1"),
    (Join-Path $WindowsRoot "Backup-IwsClientForCleanReinstall.ps1"),
    (Join-Path $WindowsRoot "Restore-IwsClientAfterFailedClean.ps1")
)
foreach ($file in $files) {
    $tokens = $null
    $errors = $null
    $null = [Management.Automation.Language.Parser]::ParseFile($file, [ref]$tokens, [ref]$errors)
    if ($errors.Count -ne 0) {
        foreach ($error in $errors) { [Console]::Error.WriteLine($error.Message) }
        throw "PowerShell parser rejected an IWS installer script."
    }
}

$backupPlan = (& (Join-Path $WindowsRoot "Backup-IwsClientForCleanReinstall.ps1") `
    -BundleRoot $WindowsRoot -RecoveryRoot "C:\ProgramData\IWS\Recovery\fixture" -PlanOnly) -join "`n"
if (-not $backupPlan.Contains("protected local recovery storage")) {
    throw "Clean backup PlanOnly omitted protected recovery."
}
$restorePlan = (& (Join-Path $WindowsRoot "Restore-IwsClientAfterFailedClean.ps1") `
    -BundleRoot $WindowsRoot -RecoveryRoot "C:\ProgramData\IWS\Recovery\fixture" -PlanOnly) -join "`n"
if (-not $restorePlan.Contains("restore protected previous IWS service")) {
    throw "Clean recovery PlanOnly omitted previous-state restoration."
}
$shellPlan = (& (Join-Path $PackagingRoot "Install-IwsWebViewShellDevice.ps1") `
    -BundleRoot $PackagingRoot -PlanOnly) -join "`n"
if (-not $shellPlan.Contains("create one IWS Start Menu shortcut")) {
    throw "Shell PlanOnly omitted the ordinary app entrypoint."
}
Write-Output "IWS_INSTALLER_SCRIPT_TESTS=pass"
