#Requires -Version 5.1

$ErrorActionPreference = "Stop"
$modulePath = Join-Path (Split-Path -Parent $PSScriptRoot) "IwsPrivateTransport.psm1"
Import-Module $modulePath -Force

$passed = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        throw $Message
    }
    $script:passed += 1
}

function Assert-Throws {
    param([scriptblock]$Action, [string]$Message)
    try {
        & $Action
    }
    catch {
        $script:passed += 1
        return
    }
    throw $Message
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("iws-poc-tests-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempRoot | Out-Null

try {
    $payloadPath = Join-Path $tempRoot "payload.json"
    @{
        device_name = "DEVMACHINE-IWS"
        management_server = "https://api.netbird.io:443"
        setup_key_file = "C:\ProgramData\IWS\Provisioning\one-use.key"
        iws_entrypoint = "http://100.83.246.85:443/"
    } | ConvertTo-Json | Set-Content -LiteralPath $payloadPath -Encoding UTF8

    $payload = Read-IwsPayload -Path $payloadPath
    Assert-True ($payload.device_name -eq "DEVMACHINE-IWS") "valid device name was not preserved"
    Assert-True ($payload.management_server -eq "https://api.netbird.io/") "management URL was not normalized"
    Assert-True ($payload.iws_entrypoint -eq "http://100.83.246.85:443/") "portal URL was not preserved"

    $badManagement = Join-Path $tempRoot "bad-management.json"
    @{
        device_name = "DEVMACHINE-IWS"
        management_server = "http://api.netbird.io:443"
        setup_key_file = "C:\ProgramData\IWS\Provisioning\one-use.key"
        iws_entrypoint = "http://100.83.246.85:443/"
    } | ConvertTo-Json | Set-Content -LiteralPath $badManagement -Encoding UTF8
    Assert-Throws { Read-IwsPayload -Path $badManagement } "HTTP management URL was accepted"

    $badPortal = Join-Path $tempRoot "bad-portal.json"
    @{
        device_name = "DEVMACHINE-IWS"
        management_server = "https://api.netbird.io:443"
        setup_key_file = "C:\ProgramData\IWS\Provisioning\one-use.key"
        iws_entrypoint = "file:///C:/portal.html"
    } | ConvertTo-Json | Set-Content -LiteralPath $badPortal -Encoding UTF8
    Assert-Throws { Read-IwsPayload -Path $badPortal } "non-HTTP portal URL was accepted"

    $badName = Join-Path $tempRoot "bad-name.json"
    @{
        device_name = "bad name"
        management_server = "https://api.netbird.io:443"
        setup_key_file = "C:\ProgramData\IWS\Provisioning\one-use.key"
        iws_entrypoint = "http://100.83.246.85:443/"
    } | ConvertTo-Json | Set-Content -LiteralPath $badName -Encoding UTF8
    Assert-Throws { Read-IwsPayload -Path $badName } "invalid device name was accepted"

    $stateDir = "C:\ProgramData\IWS\Transport"
    $serviceArgs = Get-IwsServiceInstallArguments -StateDir $stateDir
    $serviceText = $serviceArgs -join " "
    foreach ($required in @(
        "--service IWSPrivateTransport",
        "--daemon-addr npipe://iws-private-transport",
        "--disable-profiles",
        "--disable-networks",
        "--service-env NB_STATE_DIR=C:\ProgramData\IWS\Transport"
    )) {
        Assert-True ($serviceText.Contains($required)) "missing service contract: $required"
    }
    Assert-True (-not $serviceText.Contains("--disable-update-settings")) `
        "bootstrap daemon blocked its own enrollment configuration"

    $lockArgs = Get-IwsServiceLockArguments -StateDir $stateDir
    $lockText = $lockArgs -join " "
    foreach ($required in @(
        "--service IWSPrivateTransport",
        "--daemon-addr npipe://iws-private-transport",
        "service reconfigure",
        "--disable-profiles",
        "--disable-update-settings",
        "--disable-networks",
        "--service-env NB_STATE_DIR=C:\ProgramData\IWS\Transport"
    )) {
        Assert-True ($lockText.Contains($required)) "missing post-enrollment service lock: $required"
    }

    $enrollmentArgs = Get-IwsEnrollmentArguments -Payload $payload
    $enrollmentText = $enrollmentArgs -join " "
    foreach ($required in @(
        "--setup-key-file C:\ProgramData\IWS\Provisioning\one-use.key",
        "--interface-name IWSPrivate",
        "--disable-client-routes",
        "--disable-server-routes",
        "--disable-dns",
        "--disable-ipv6",
        "--block-inbound",
        "--block-lan-access"
    )) {
        Assert-True ($enrollmentText.Contains($required)) "missing enrollment contract: $required"
    }
    Assert-True (-not $enrollmentText.Contains("--disable-firewall")) "firewall was disabled"
    Assert-True (-not $enrollmentText.Contains("netbird-ui")) "desktop UI entered enrollment"

    $artifactPath = Join-Path $tempRoot "artifact.bin"
    [IO.File]::WriteAllText($artifactPath, "abc", [Text.Encoding]::ASCII)
    Assert-IwsArtifact -Path $artifactPath -ExpectedSha256 "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    $script:passed += 1
    Assert-Throws {
        Assert-IwsArtifact -Path $artifactPath -ExpectedSha256 ("0" * 64)
    } "artifact hash mismatch was accepted"

    $nativeOutput = Invoke-IwsNativeSanitized `
        -FilePath "$env:SystemRoot\System32\cmd.exe" `
        -Arguments @("/d", "/c", "echo HARMLESS_STDERR 1>&2 & exit /b 0") `
        -FailureMessage "native warning fixture failed"
    Assert-True ([string]::IsNullOrWhiteSpace(($nativeOutput | Out-String))) `
        "successful native stderr was exposed"
    Assert-Throws {
        Invoke-IwsNativeSanitized `
            -FilePath "$env:SystemRoot\System32\cmd.exe" `
            -Arguments @("/d", "/c", "exit /b 7") `
            -FailureMessage "sanitized native failure"
    } "nonzero native exit was accepted"
    $notReady = Test-IwsNativeSuccess `
        -FilePath "$env:SystemRoot\System32\cmd.exe" `
        -Arguments @("/d", "/c", "echo NOT_READY 1>&2 & exit /b 1")
    Assert-True ($notReady -eq $false) `
        "nonzero readiness probe was accepted"
    $isReady = Test-IwsNativeSuccess `
        -FilePath "$env:SystemRoot\System32\cmd.exe" `
        -Arguments @("/d", "/c", "echo READY_WARNING 1>&2 & exit /b 0")
    Assert-True $isReady `
        "successful readiness probe with stderr was rejected"

    $windowsRoot = Split-Path -Parent $PSScriptRoot
    $installerPath = Join-Path $windowsRoot "Install-IwsPrivateTransport.ps1"
    $removerPath = Join-Path $windowsRoot "Remove-IwsClientPoc.ps1"
    Assert-True (Test-Path -LiteralPath $installerPath -PathType Leaf) "installer script is missing"
    Assert-True (Test-Path -LiteralPath $removerPath -PathType Leaf) "removal script is missing"
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $windowsRoot "Launch-IwsPoc.ps1"))) `
        "obsolete Edge app-mode launcher is present"

    $bundleRoot = Join-Path $tempRoot "bundle"
    New-Item -ItemType Directory -Path $bundleRoot | Out-Null
    [IO.File]::WriteAllText((Join-Path $bundleRoot "iws-transport.exe"), "not-an-executable", [Text.Encoding]::ASCII)
    [IO.File]::WriteAllText((Join-Path $bundleRoot "wintun.dll"), "not-a-library", [Text.Encoding]::ASCII)

    $planKeyPath = Join-Path $tempRoot "plan-one-use.key"
    $sentinel = "SENSITIVE-TEST-CREDENTIAL-MUST-NOT-APPEAR"
    Set-Content -LiteralPath $planKeyPath -Value $sentinel -NoNewline
    $planPayloadPath = Join-Path $tempRoot "plan-payload.json"
    @{
        device_name = "DEVMACHINE-IWS"
        management_server = "https://api.netbird.io:443"
        setup_key_file = $planKeyPath
        iws_entrypoint = "http://100.83.246.85:443/"
    } | ConvertTo-Json | Set-Content -LiteralPath $planPayloadPath -Encoding UTF8

    $planOutput = (& $installerPath -PayloadPath $planPayloadPath -BundleRoot $bundleRoot -PlanOnly) -join "`n"
    Assert-True (Test-Path -LiteralPath $planKeyPath -PathType Leaf) "PlanOnly deleted the key file"
    Assert-True ($planOutput.Contains("PLAN install IWS private transport service")) "PlanOnly omitted service installation"
    Assert-True ($planOutput.Contains("PLAN enroll IWS device from protected one-use file")) "PlanOnly omitted enrollment"
    Assert-True (-not $planOutput.Contains($sentinel)) "PlanOnly exposed key contents"

    $liveKeyPath = Join-Path $tempRoot "live-one-use.key"
    Set-Content -LiteralPath $liveKeyPath -Value $sentinel -NoNewline
    $livePayloadPath = Join-Path $tempRoot "live-payload.json"
    @{
        device_name = "DEVMACHINE-IWS"
        management_server = "https://api.netbird.io:443"
        setup_key_file = $liveKeyPath
        iws_entrypoint = "http://100.83.246.85:443/"
    } | ConvertTo-Json | Set-Content -LiteralPath $livePayloadPath -Encoding UTF8

    $serviceWasPresent = [bool](Get-Service -Name "IWSPrivateTransport" -ErrorAction SilentlyContinue)
    $liveError = ""
    try {
        & $installerPath -PayloadPath $livePayloadPath -BundleRoot $bundleRoot
    }
    catch {
        $liveError = $_.Exception.Message
    }
    Assert-True (-not [string]::IsNullOrWhiteSpace($liveError)) "invalid live bundle did not fail"
    Assert-True (-not (Test-Path -LiteralPath $liveKeyPath)) "live preflight failure retained one-use material"
    Assert-True (-not $liveError.Contains($sentinel)) "live failure exposed key contents"
    $serviceIsPresent = [bool](Get-Service -Name "IWSPrivateTransport" -ErrorAction SilentlyContinue)
    Assert-True ($serviceIsPresent -eq $serviceWasPresent) "preflight failure changed service presence"

    $removePlan = (& $removerPath -PlanOnly) -join "`n"
    Assert-True ($removePlan.Contains("IWSPrivateTransport")) "removal PlanOnly omitted IWS service"
    Assert-True (-not $removePlan.Contains("RemoveIdentity=True")) "removal plan deletes identity by default"

    Write-Output "PASS $passed Windows POC contract assertions"
}
finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
