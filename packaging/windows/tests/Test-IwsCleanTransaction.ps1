#Requires -Version 5.1

[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$ModulePath)

$ErrorActionPreference = "Stop"
Import-Module $ModulePath -Force
$passed = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
    $script:passed += 1
}

function New-FixtureComponent {
    param([string]$Root, [string]$Name, [string]$Value)
    $path = Join-Path $Root $Name
    New-Item -ItemType Directory -Path $path -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $path "identity.txt") -Value $Value -NoNewline
    return @{ Name = $Name; Source = $path }
}

function Assert-Identity {
    param([string]$Root, [string]$Name, [string]$Value)
    Assert-True ((Get-Content -LiteralPath (Join-Path $Root "$Name\identity.txt") -Raw) -eq $Value) `
        "$Name identity was not preserved"
}

$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ("iws-clean-transaction-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
try {
    # R1: a command failure immediately after the transaction marker must not delete untouched originals.
    $case1 = Join-Path $fixtureRoot "after-marker"
    $active1 = Join-Path $case1 "active"
    $recovery1 = Join-Path $case1 "recovery"
    $components1 = @(
        (New-FixtureComponent $active1 "State" "old-state"),
        (New-FixtureComponent $active1 "Client" "old-client")
    )
    $commands1 = [Collections.Generic.List[string]]::new()
    $case1Failed = $false
    try {
        Invoke-IwsCleanRetirement -RecoveryRoot $recovery1 -Components $components1 `
            -BeforeRetirement {
                $commands1.Add("remove-owned-integration")
                throw "fixture command failure after marker"
            } -AfterRetirement { $commands1.Add("remove-owned-service") }
    }
    catch { $case1Failed = $true }
    Assert-True $case1Failed "after-marker fixture did not fail"
    Restore-IwsCleanRetirement -RecoveryRoot $recovery1 -Components $components1 `
        -BeforeReplacementRemoval { $commands1.Add("remove-replacement") } `
        -AfterPreviousRestore { $commands1.Add("restore-owned-integration") }
    Assert-Identity $active1 "State" "old-state"
    Assert-Identity $active1 "Client" "old-client"
    Assert-True (-not $commands1.Contains("remove-replacement")) `
        "restore removed an untouched original service after marker-only failure"

    # R1: failure between moves restores moved data and leaves untouched originals in place.
    $case2 = Join-Path $fixtureRoot "between-moves"
    $active2 = Join-Path $case2 "active"
    $recovery2 = Join-Path $case2 "recovery"
    $components2 = @(
        (New-FixtureComponent $active2 "State" "old-state"),
        (New-FixtureComponent $active2 "Client" "old-client")
    )
    $case2Failed = $false
    try {
        Invoke-IwsCleanRetirement -RecoveryRoot $recovery2 -Components $components2 `
            -BeforeRetirement {
                New-Item -ItemType Directory -Path (Join-Path $recovery2 "Previous\Client") -Force | Out-Null
            } -AfterRetirement { }
    }
    catch { $case2Failed = $true }
    Assert-True $case2Failed "between-moves fixture did not fail"
    $case3Failed = $false
    try {
        Restore-IwsCleanRetirement -RecoveryRoot $recovery2 -Components $components2 `
            -BeforeReplacementRemoval { } -AfterPreviousRestore { }
    }
    catch { }
    Assert-Identity $active2 "State" "old-state"
    Assert-Identity $active2 "Client" "old-client"
    Assert-True (Test-Path -LiteralPath $recovery2) `
        "ambiguous recovery material was deleted after between-moves failure"

    # R1: a marker-write failure after a successful move recovers from verified backup presence.
    $case3 = Join-Path $fixtureRoot "marker-write"
    $active3 = Join-Path $case3 "active"
    $recovery3 = Join-Path $case3 "recovery"
    $components3 = @((New-FixtureComponent $active3 "State" "old-state"))
    try {
        Invoke-IwsCleanRetirement -RecoveryRoot $recovery3 -Components $components3 `
            -BeforeRetirement {
                New-Item -ItemType Directory -Path (Join-Path $recovery3 "State.retired") -Force | Out-Null
            } -AfterRetirement { }
    }
    catch { $case3Failed = $true }
    Assert-True $case3Failed "marker-write fixture did not fail"
    Restore-IwsCleanRetirement -RecoveryRoot $recovery3 -Components $components3 `
        -BeforeReplacementRemoval { } -AfterPreviousRestore { }
    Assert-Identity $active3 "State" "old-state"

    # R2: established replacement enrollment remains active and the old identity remains recoverable.
    $case4 = Join-Path $fixtureRoot "post-enrollment"
    $active4 = Join-Path $case4 "active"
    $recovery4 = Join-Path $case4 "recovery"
    $components4 = @(
        (New-FixtureComponent $active4 "State" "old-state"),
        (New-FixtureComponent $active4 "Receipt" "old-receipt")
    )
    Invoke-IwsCleanRetirement -RecoveryRoot $recovery4 -Components $components4 `
        -BeforeRetirement { } -AfterRetirement { }
    $null = New-FixtureComponent $active4 "State" "new-state"
    New-Item -ItemType Directory -Path (Join-Path $active4 "Receipt") -Force | Out-Null
    $receiptPath = Join-Path $active4 "Receipt\receipt.json"
    Set-Content -LiteralPath $receiptPath -Value `
        '{"schemaVersion":1,"deviceId":"devicea","generation":2,"clientCheckpoint":"secure-client-v1.0.1"}' -NoNewline
    Write-IwsCleanEnrollmentEstablished -RecoveryRoot $recovery4 -DeviceId "devicea" `
        -Generation 2 -ClientCheckpoint "secure-client-v1.0.1"
    $disposition = Get-IwsCleanFailureDisposition -RecoveryRoot $recovery4 `
        -ReceiptPath $receiptPath -DeviceId "devicea" -Generation 2
    Assert-True ($disposition -eq "PreserveNewEnrollment") `
        "post-enrollment downstream failure selected old-state rollback"
    Assert-True ((Get-IwsCleanFailureDisposition -RecoveryRoot $recovery4 `
        -ReceiptPath (Join-Path $active4 "Receipt\missing.json") `
        -DeviceId "devicea" -Generation 2) -eq "PreserveNewEnrollment") `
        "missing post-enrollment receipt authorized deletion of established replacement identity"
    Assert-Identity $active4 "State" "new-state"
    Assert-True ((Get-Content -LiteralPath (Join-Path $recovery4 "Previous\State\identity.txt") -Raw) -eq "old-state") `
        "previous state was not retained in recovery storage"
    Assert-True (Test-IwsCleanArtifactAlreadyConsumed -RecoveryRoot $recovery4 `
        -DeviceId "devicea" -Generation 2) "consumed generation was not remembered"

    # R1: a changed backup is not verified and must never authorize deleting an active replacement.
    $case5 = Join-Path $fixtureRoot "changed-backup"
    $active5 = Join-Path $case5 "active"
    $recovery5 = Join-Path $case5 "recovery"
    $components5 = @((New-FixtureComponent $active5 "State" "old-state"))
    Invoke-IwsCleanRetirement -RecoveryRoot $recovery5 -Components $components5 `
        -BeforeRetirement { } -AfterRetirement { }
    $null = New-FixtureComponent $active5 "State" "new-state"
    Set-Content -LiteralPath (Join-Path $recovery5 "Previous\State\identity.txt") `
        -Value "changed-backup" -NoNewline
    $case5Failed = $false
    try {
        Restore-IwsCleanRetirement -RecoveryRoot $recovery5 -Components $components5 `
            -BeforeReplacementRemoval { } -AfterPreviousRestore { }
    }
    catch { $case5Failed = $true }
    Assert-True $case5Failed "changed backup was accepted as verified recovery material"
    Assert-Identity $active5 "State" "new-state"
    Assert-True (Test-Path -LiteralPath $recovery5) `
        "changed recovery material was deleted after restore refusal"

    Write-Output "IWS_CLEAN_TRANSACTION_TESTS=pass ($passed assertions)"
}
finally {
    Remove-Item -LiteralPath $fixtureRoot -Recurse -Force -ErrorAction SilentlyContinue
}
