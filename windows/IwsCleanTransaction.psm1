Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-IwsCleanComponentName {
    param([Parameter(Mandatory = $true)][string]$Name)
    if ($Name -notmatch '^[A-Za-z][A-Za-z0-9]{0,31}$') {
        throw "IWS clean transaction component name is invalid."
    }
}

function Get-IwsCleanComponentFingerprint {
    param([Parameter(Mandatory = $true)][string]$Path)
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    if (-not (Test-Path -LiteralPath $full)) { throw "IWS clean component is unavailable." }
    $lines = [Collections.Generic.List[string]]::new()
    $rootItem = Get-Item -LiteralPath $full -Force
    if (($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "IWS clean component contains a reparse point."
    }
    if ($rootItem.PSIsContainer) {
        foreach ($item in @(Get-ChildItem -LiteralPath $full -Recurse -Force | Sort-Object FullName)) {
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "IWS clean component contains a reparse point."
            }
            $relative = $item.FullName.Substring($full.Length).TrimStart('\').Replace('\', '/')
            if ($item.PSIsContainer) { $lines.Add("D|" + $relative) }
            else {
                $hash = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
                $lines.Add("F|" + $relative + "|" + $item.Length + "|" + $hash)
            }
        }
    }
    else {
        $hash = (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()
        $lines.Add("F||" + $rootItem.Length + "|" + $hash)
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Invoke-IwsCleanRetirement {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$RecoveryRoot,
        [Parameter(Mandatory = $true)][object[]]$Components,
        [Parameter(Mandatory = $true)][scriptblock]$BeforeRetirement,
        [Parameter(Mandatory = $true)][scriptblock]$AfterRetirement,
        [scriptblock]$ProtectRecovery = { }
    )

    if (Test-Path -LiteralPath $RecoveryRoot) {
        if (@(Get-ChildItem -LiteralPath $RecoveryRoot -Force).Count -ne 0) {
            throw "IWS recovery transaction path is not empty."
        }
    }
    else { New-Item -ItemType Directory -Path $RecoveryRoot -Force | Out-Null }
    & $ProtectRecovery
    $previous = Join-Path $RecoveryRoot "Previous"
    New-Item -ItemType Directory -Path $previous -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $RecoveryRoot "transaction.started") `
        -Value "IWS_CLEAN_TRANSACTION_V1" -Encoding ASCII

    & $BeforeRetirement
    $seen = @{}
    foreach ($component in $Components) {
        $name = [string]$component.Name
        $source = [IO.Path]::GetFullPath([string]$component.Source)
        Assert-IwsCleanComponentName -Name $name
        if ($seen.ContainsKey($name)) { throw "Duplicate IWS clean transaction component." }
        $seen[$name] = $true
        $backup = Join-Path $previous $name
        if (Test-Path -LiteralPath $source) {
            if (Test-Path -LiteralPath $backup) {
                throw "IWS clean recovery destination already exists."
            }
            $beforeFingerprint = Get-IwsCleanComponentFingerprint -Path $source
            Move-Item -LiteralPath $source -Destination $backup
            if ((Test-Path -LiteralPath $source) -or -not (Test-Path -LiteralPath $backup)) {
                throw "IWS clean component retirement could not be verified."
            }
            $afterFingerprint = Get-IwsCleanComponentFingerprint -Path $backup
            if ($afterFingerprint -ne $beforeFingerprint) {
                throw "IWS clean component backup verification failed."
            }
            Set-Content -LiteralPath (Join-Path $RecoveryRoot ($name + ".fingerprint")) `
                -Value $afterFingerprint -Encoding ASCII
            Set-Content -LiteralPath (Join-Path $RecoveryRoot ($name + ".retired")) `
                -Value "IWS_COMPONENT_RETIRED_V1" -Encoding ASCII
        }
        else {
            Set-Content -LiteralPath (Join-Path $RecoveryRoot ($name + ".absent")) `
                -Value "IWS_COMPONENT_ABSENT_V1" -Encoding ASCII
        }
    }
    & $AfterRetirement
    Set-Content -LiteralPath (Join-Path $RecoveryRoot "transaction.complete") `
        -Value "IWS_CLEAN_TRANSACTION_V1" -Encoding ASCII
}

function Restore-IwsCleanRetirement {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$RecoveryRoot,
        [Parameter(Mandatory = $true)][object[]]$Components,
        [Parameter(Mandatory = $true)][scriptblock]$BeforeReplacementRemoval,
        [Parameter(Mandatory = $true)][scriptblock]$AfterPreviousRestore
    )

    if (-not (Test-Path -LiteralPath (Join-Path $RecoveryRoot "transaction.started") -PathType Leaf)) {
        throw "IWS clean recovery transaction marker is unavailable."
    }
    $complete = Test-Path -LiteralPath (Join-Path $RecoveryRoot "transaction.complete") -PathType Leaf
    if ($complete) { & $BeforeReplacementRemoval }
    $previous = Join-Path $RecoveryRoot "Previous"
    foreach ($component in $Components) {
        $name = [string]$component.Name
        $source = [IO.Path]::GetFullPath([string]$component.Source)
        Assert-IwsCleanComponentName -Name $name
        $backup = Join-Path $previous $name
        $fingerprintPath = Join-Path $RecoveryRoot ($name + ".fingerprint")
        $retired = Test-Path -LiteralPath (Join-Path $RecoveryRoot ($name + ".retired")) -PathType Leaf
        $absent = Test-Path -LiteralPath (Join-Path $RecoveryRoot ($name + ".absent")) -PathType Leaf
        if (Test-Path -LiteralPath $backup) {
            if (-not (Test-Path -LiteralPath $fingerprintPath -PathType Leaf)) {
                throw "IWS recovery fingerprint is missing; active component was preserved."
            }
            $expectedFingerprint = (Get-Content -LiteralPath $fingerprintPath -Raw).Trim()
            if ($expectedFingerprint -notmatch '^[0-9a-f]{64}$' -or
                (Get-IwsCleanComponentFingerprint -Path $backup) -ne $expectedFingerprint) {
                throw "IWS recovery backup verification failed; active component was preserved."
            }
            if (Test-Path -LiteralPath $source) {
                if (-not $complete -or -not $retired) {
                    throw "IWS recovery found both an untouched original and ambiguous backup."
                }
                Remove-Item -LiteralPath $source -Recurse -Force
            }
            New-Item -ItemType Directory -Path (Split-Path -Parent $source) -Force | Out-Null
            Move-Item -LiteralPath $backup -Destination $source
            if (-not (Test-Path -LiteralPath $source) -or (Test-Path -LiteralPath $backup)) {
                throw "IWS previous component restoration could not be verified."
            }
            if ((Get-IwsCleanComponentFingerprint -Path $source) -ne $expectedFingerprint) {
                throw "IWS restored component verification failed."
            }
        }
        elseif ($retired) {
            throw "IWS recovery backup is missing; active component was preserved."
        }
        elseif ($absent -and $complete -and (Test-Path -LiteralPath $source)) {
            Remove-Item -LiteralPath $source -Recurse -Force
        }
    }
    & $AfterPreviousRestore
    Remove-Item -LiteralPath $RecoveryRoot -Recurse -Force
}

function Write-IwsCleanEnrollmentEstablished {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$RecoveryRoot,
        [Parameter(Mandatory = $true)][string]$DeviceId,
        [Parameter(Mandatory = $true)][int]$Generation,
        [Parameter(Mandatory = $true)][string]$ClientCheckpoint
    )
    if (-not (Test-Path -LiteralPath (Join-Path $RecoveryRoot "transaction.complete") -PathType Leaf) -or
        $DeviceId -notmatch '^[a-z0-9]{1,40}$' -or $Generation -lt 1 -or
        $ClientCheckpoint -ne "secure-client-v1.0.1") {
        throw "IWS established enrollment record is invalid."
    }
    [pscustomobject]@{
        schemaVersion = 1
        status = "ESTABLISHED"
        deviceId = $DeviceId
        generation = $Generation
        clientCheckpoint = $ClientCheckpoint
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $RecoveryRoot "new-enrollment.json") -Encoding UTF8
}

function Read-IwsCleanEnrollmentRecord {
    param([Parameter(Mandatory = $true)][string]$RecoveryRoot)
    $path = Join-Path $RecoveryRoot "new-enrollment.json"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    try { $record = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json }
    catch { return $null }
    if ([int]$record.schemaVersion -ne 1 -or [string]$record.status -ne "ESTABLISHED" -or
        [string]$record.deviceId -notmatch '^[a-z0-9]{1,40}$' -or [int]$record.generation -lt 1 -or
        [string]$record.clientCheckpoint -ne "secure-client-v1.0.1") { return $null }
    return $record
}

function Get-IwsCleanFailureDisposition {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$RecoveryRoot,
        [Parameter(Mandatory = $true)][string]$ReceiptPath,
        [Parameter(Mandatory = $true)][string]$DeviceId,
        [Parameter(Mandatory = $true)][int]$Generation
    )
    $record = Read-IwsCleanEnrollmentRecord -RecoveryRoot $RecoveryRoot
    if (-not $record -or [string]$record.deviceId -ne $DeviceId -or
        [int]$record.generation -ne $Generation) {
        return "RestorePrevious"
    }
    # The protected established record is authoritative. A missing or damaged receipt must
    # never turn an established replacement identity into a deletion target.
    return "PreserveNewEnrollment"
}

function Test-IwsCleanArtifactAlreadyConsumed {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$RecoveryRoot,
        [Parameter(Mandatory = $true)][string]$DeviceId,
        [Parameter(Mandatory = $true)][int]$Generation
    )
    $record = Read-IwsCleanEnrollmentRecord -RecoveryRoot $RecoveryRoot
    return [bool]($record -and [string]$record.deviceId -eq $DeviceId -and
        [int]$record.generation -eq $Generation)
}

Export-ModuleMember -Function @(
    "Invoke-IwsCleanRetirement",
    "Restore-IwsCleanRetirement",
    "Write-IwsCleanEnrollmentEstablished",
    "Get-IwsCleanFailureDisposition",
    "Test-IwsCleanArtifactAlreadyConsumed"
)
