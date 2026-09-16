#Requires -Version 5.1

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BundleRoot,
    [Parameter(Mandatory = $true)][string]$RecoveryRoot,
    [switch]$PlanOnly
)

$ErrorActionPreference = "Stop"
$pins = Import-PowerShellDataFile -LiteralPath (Join-Path $BundleRoot "pins.psd1")
Import-Module (Join-Path $BundleRoot "IwsPrivateTransport.psm1") -Force
Import-Module (Join-Path $BundleRoot "IwsCleanTransaction.psm1") -Force

if ($PlanOnly) {
    Write-Output "PLAN restore only components with verified previous backups"
    Write-Output "PLAN preserve untouched originals and ambiguous recovery material"
    Write-Output "PLAN restore protected previous IWS service, identity, shell, and uninstall registration"
    return
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "IWS recovery requires ordinary Administrator approval."
}
$recovery = [IO.Path]::GetFullPath($RecoveryRoot).TrimEnd('\')
$allowedRoot = [IO.Path]::GetFullPath("C:\ProgramData\IWS\Recovery").TrimEnd('\')
if (-not $recovery.StartsWith($allowedRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
    -not (Test-Path -LiteralPath $recovery -PathType Container)) {
    throw "IWS recovery storage is unavailable."
}

$components = @(
    @{ Name = "Transport"; Source = $pins.InstallRoot },
    @{ Name = "Client"; Source = $pins.ClientRoot },
    @{ Name = "Installer"; Source = "C:\Program Files\IWS\Installer" },
    @{ Name = "State"; Source = $pins.StateRoot },
    @{ Name = "Receipt"; Source = "C:\ProgramData\IWS\Install" }
)
Restore-IwsCleanRetirement -RecoveryRoot $recovery -Components $components `
    -BeforeReplacementRemoval {
        $currentTransport = Join-Path $pins.InstallRoot "iws-transport.exe"
        $replacementService = Get-Service -Name $pins.ServiceName -ErrorAction SilentlyContinue
        if ($replacementService) {
            $serviceConfig = Get-CimInstance -ClassName Win32_Service `
                -Filter ("Name='" + $pins.ServiceName + "'") -ErrorAction Stop
            if (-not $serviceConfig -or -not (Test-IwsServiceCommandPathOwned `
                -CommandLine ([string]$serviceConfig.PathName) -ExpectedPath $currentTransport) -or
                -not (Test-Path -LiteralPath $currentTransport -PathType Leaf)) {
                throw "Replacement service ownership is unclear; automatic recovery refused."
            }
            if ($replacementService.Status -ne "Stopped") {
                Stop-Service -Name $pins.ServiceName
                $replacementService.WaitForStatus("Stopped", [TimeSpan]::FromSeconds(20))
            }
            $null = & $currentTransport --service $pins.ServiceName `
                --daemon-addr $pins.DaemonAddress service uninstall 2>&1
            if (Get-Service -Name $pins.ServiceName -ErrorAction SilentlyContinue) {
                $null = & sc.exe delete $pins.ServiceName 2>&1
            }
        }
    } `
    -AfterPreviousRestore {
        $restoredTransport = Join-Path $pins.InstallRoot "iws-transport.exe"
        if (Test-Path -LiteralPath $restoredTransport -PathType Leaf) {
            Assert-IwsArtifact -Path $restoredTransport -ExpectedSha256 $pins.TransportSha256
            $env:NB_STATE_DIR = $pins.StateRoot
            $restoredService = Get-Service -Name $pins.ServiceName -ErrorAction SilentlyContinue
            if (-not $restoredService) {
                Invoke-IwsNativeSanitized -FilePath $restoredTransport `
                    -Arguments (Get-IwsServiceInstallArguments -StateDir $pins.StateRoot) `
                    -FailureMessage "Previous IWS service recovery failed."
            }
            Set-Service -Name $pins.ServiceName -DisplayName $pins.ServiceDisplayName -StartupType Automatic
            $restoredService = Get-Service -Name $pins.ServiceName -ErrorAction Stop
            if ($restoredService.Status -ne "Running") { Start-Service -Name $pins.ServiceName }
            (Get-Service -Name $pins.ServiceName).WaitForStatus("Running", [TimeSpan]::FromSeconds(20))
        }

        $trust = Join-Path $pins.ClientRoot "Install-IwsProductionTrust.ps1"
        $certificate = Join-Path $pins.ClientRoot "iws-production-root-ca.crt"
        if ((Test-Path -LiteralPath $trust -PathType Leaf) -and
            (Test-Path -LiteralPath $certificate -PathType Leaf)) {
            & $trust -CertificatePath $certificate
        }
        $setBoundary = Join-Path $pins.ClientRoot "Set-IwsWebViewBoundary.ps1"
        if (Test-Path -LiteralPath $setBoundary -PathType Leaf) {
            & $setBoundary -ProgramPaths @(
                (Join-Path $pins.ClientRoot "IwsClient.exe"),
                (Join-Path $pins.ClientRoot "IwsBoundaryProbe.exe"),
                (Join-Path $pins.ClientRoot "WebView2Fixed\msedgewebview2.exe")
            )
        }
        $client = Join-Path $pins.ClientRoot "IwsClient.exe"
        if (Test-Path -LiteralPath $client -PathType Leaf) {
            $shortcutPath = Join-Path ([Environment]::GetFolderPath("CommonPrograms")) "IWS.lnk"
            $shell = New-Object -ComObject WScript.Shell
            $shortcut = $shell.CreateShortcut($shortcutPath)
            $shortcut.TargetPath = $client
            $shortcut.WorkingDirectory = $pins.ClientRoot
            $shortcut.IconLocation = $client + ",0"
            $shortcut.Save()
        }
        $uninstaller = "C:\Program Files\IWS\Installer\IwsUninstall.exe"
        if (Test-Path -LiteralPath $uninstaller -PathType Leaf) {
            $key = "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\IWS Secure Client"
            New-Item -Path $key -Force | Out-Null
            New-ItemProperty -Path $key -Name DisplayName -Value "IWS Secure Client" -PropertyType String -Force | Out-Null
            New-ItemProperty -Path $key -Name DisplayVersion -Value "1.0.1" -PropertyType String -Force | Out-Null
            New-ItemProperty -Path $key -Name Publisher -Value "Impact Wiring Solutions" -PropertyType String -Force | Out-Null
            New-ItemProperty -Path $key -Name UninstallString -Value ('"' + $uninstaller + '"') -PropertyType String -Force | Out-Null
            New-ItemProperty -Path $key -Name NoModify -Value 1 -PropertyType DWord -Force | Out-Null
            New-ItemProperty -Path $key -Name NoRepair -Value 1 -PropertyType DWord -Force | Out-Null
        }
    }
Write-Output "IWS_CLEAN_RECOVERY_COMPLETE=yes"
