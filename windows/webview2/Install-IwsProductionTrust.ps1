#Requires -Version 5.1
# Pinned public root and exact-host DNS only. State inherits Program Files protection.
[CmdletBinding()]
param([string]$CertificatePath = (Join-Path $PSScriptRoot 'iws-production-root-ca.crt'), [switch]$Remove)
$ErrorActionPreference = 'Stop'
$statePath = 'C:\Program Files\IWS\Client\production-trust-state.json'
$expected = '3976d486cf804696206b98fccb56c2315f29a6690a0272891c562fac2b48a781'
$pem = [IO.File]::ReadAllText($CertificatePath)
if ($pem -match 'PRIVATE KEY' -or $pem -notmatch '\A\s*-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\r\n]+-----END CERTIFICATE-----\s*\z') {
    throw 'Only one public PEM certificate is permitted'
}
$cert = [Security.Cryptography.X509Certificates.X509Certificate2]::new($CertificatePath)
if ($cert.HasPrivateKey) { throw 'Private key material is prohibited' }
$sha = [Security.Cryptography.SHA256]::Create()
try { $actual = ([BitConverter]::ToString($sha.ComputeHash($cert.RawData))).Replace('-', '').ToLowerInvariant() }
finally { $sha.Dispose() }
if ($actual -ne $expected) { throw 'IWS public CA fingerprint mismatch' }
$state = [pscustomobject]@{ CertificateOwned = $false; RuleName = $null }
if (Test-Path -LiteralPath $statePath) { $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json }
function Save-State { $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8 }
$store = [Security.Cryptography.X509Certificates.X509Store]::new('Root','LocalMachine')
try {
    $store.Open([Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
    if ($Remove) {
        if ($state.RuleName) {
            $owned = @(Get-DnsClientNrptRule | Where-Object Name -eq $state.RuleName)
            if ($owned.Count -gt 0) {
                if ($owned.Count -ne 1 -or @($owned[0].Namespace).Count -ne 1 -or
                    $owned[0].Namespace[0] -ne 'portal.iws.internal' -or
                    (@($owned[0].NameServers) -join ',') -ne '100.83.75.124') {
                    throw 'Owned IWS DNS rule changed; refusing to remove it'
                }
                Remove-DnsClientNrptRule -Name $state.RuleName -Force
            }
        }
        if ($state.CertificateOwned) { $store.Remove($cert) }
        Remove-Item -LiteralPath $statePath -Force -ErrorAction SilentlyContinue
        return
    }
    if ($cert.NotAfter.ToUniversalTime() -le [DateTime]::UtcNow -or $cert.NotBefore.ToUniversalTime() -gt [DateTime]::UtcNow) {
        throw 'IWS public CA is outside its validity period'
    }
    $rules = @(Get-DnsClientNrptRule | Where-Object { @($_.Namespace) -contains 'portal.iws.internal' })
    foreach ($rule in $rules) {
        if (@($rule.Namespace).Count -ne 1 -or (@($rule.NameServers) -join ',') -ne '100.83.75.124') {
            throw 'Conflicting IWS exact-host DNS rule exists'
        }
    }
    if ($rules.Count -gt 1) { throw 'Multiple IWS exact-host DNS rules exist' }
    if ($store.Certificates.Find([Security.Cryptography.X509Certificates.X509FindType]::FindByThumbprint,$cert.Thumbprint,$false).Count -eq 0) {
        $state.CertificateOwned = $true
        Save-State
        $store.Add($cert)
    }
    if ($rules.Count -eq 0) {
        $rule = Add-DnsClientNrptRule -Namespace 'portal.iws.internal' -NameServers '100.83.75.124' -Comment 'IWS production private HTTPS' -PassThru
        $state.RuleName = $rule.Name
        Save-State
    }
    Save-State
    if ($store.Certificates.Find([Security.Cryptography.X509Certificates.X509FindType]::FindByThumbprint,$cert.Thumbprint,$false).Count -ne 1) {
        throw 'IWS public CA installation readback failed'
    }
    $verified = @(Get-DnsClientNrptRule | Where-Object { @($_.Namespace) -contains 'portal.iws.internal' })
    if ($verified.Count -ne 1 -or (@($verified[0].NameServers) -join ',') -ne '100.83.75.124') { throw 'IWS DNS installation readback failed' }
} finally { $store.Close() }
Write-Output 'IWS_PRODUCTION_TRUST_INSTALLED=true'
