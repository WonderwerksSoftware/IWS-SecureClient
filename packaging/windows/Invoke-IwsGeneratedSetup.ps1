#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ArtifactPath,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedSha256
)
$ErrorActionPreference = "Stop"
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "IWS setup requires ordinary Administrator approval."
}
if ((Get-FileHash -LiteralPath $ArtifactPath -Algorithm SHA256).Hash -ne $ExpectedSha256) {
    throw "IWS setup artifact verification failed."
}
$process = Start-Process -FilePath $ArtifactPath -PassThru
$deadline = (Get-Date).AddMinutes(10)
while (-not $process.HasExited -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
if (-not $process.HasExited) { throw "IWS setup timed out." }
if ($process.ExitCode -ne 0) { throw "IWS setup did not complete." }
$service = Get-Service -Name "IWSPrivateTransport" -ErrorAction Stop
if ($service.Status -ne "Running" -or $service.StartType -ne "Automatic") {
    throw "IWS private connectivity is not persistent."
}
if (-not (Get-Process -Name "IwsClient" -ErrorAction SilentlyContinue)) {
    throw "IWS did not open after setup."
}
if (Get-Process -Name "netbird-ui", "netbird" -ErrorAction SilentlyContinue) {
    throw "A prohibited employee-facing transport process is present."
}
Write-Output "IWS_GENERATED_SETUP_COMPLETE=yes"
