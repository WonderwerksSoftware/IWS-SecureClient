Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:IwsServiceName = "IWSPrivateTransport"
$script:IwsDaemonAddress = "npipe://iws-private-transport"
$script:IwsInterfaceName = "IWSPrivate"

function ConvertTo-IwsAbsoluteUri {
    param(
        [Parameter(Mandatory = $true)][string]$Value,
        [Parameter(Mandatory = $true)][string]$FieldName,
        [Parameter(Mandatory = $true)][string[]]$AllowedSchemes
    )

    $parsed = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$parsed)) {
        throw "$FieldName must be an absolute URL."
    }
    if ($AllowedSchemes -notcontains $parsed.Scheme.ToLowerInvariant()) {
        throw "$FieldName uses a prohibited URL scheme."
    }
    if ([string]::IsNullOrWhiteSpace($parsed.Host) -or -not [string]::IsNullOrEmpty($parsed.UserInfo)) {
        throw "$FieldName must identify a host and contain no credentials."
    }
    return $parsed.AbsoluteUri
}

function Read-IwsPayload {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Provisioning payload file was not found."
    }
    $payload = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    foreach ($field in @("device_name", "management_server", "setup_key_file", "iws_entrypoint")) {
        if ([string]::IsNullOrWhiteSpace([string]$payload.$field)) {
            throw "Provisioning payload is missing required field: $field."
        }
    }
    if ([string]$payload.device_name -notmatch "^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$") {
        throw "device_name contains prohibited characters or is too long."
    }

    return [pscustomobject]@{
        device_name = [string]$payload.device_name
        management_server = ConvertTo-IwsAbsoluteUri `
            -Value ([string]$payload.management_server) `
            -FieldName "management_server" `
            -AllowedSchemes @("https")
        setup_key_file = [IO.Path]::GetFullPath(
            [Environment]::ExpandEnvironmentVariables([string]$payload.setup_key_file)
        )
        iws_entrypoint = ConvertTo-IwsAbsoluteUri `
            -Value ([string]$payload.iws_entrypoint) `
            -FieldName "iws_entrypoint" `
            -AllowedSchemes @("http", "https")
    }
}

function Get-IwsServiceInstallArguments {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$StateDir)

    $configPath = Join-Path $StateDir "default.json"
    $logPath = Join-Path $StateDir "client.log"
    return @(
        "--service", $script:IwsServiceName,
        "--daemon-addr", $script:IwsDaemonAddress,
        "--config", $configPath,
        "--log-file", $logPath,
        "service", "install",
        "--disable-profiles",
        "--disable-networks",
        "--service-env", "NB_STATE_DIR=$StateDir"
    )
}

function Get-IwsServiceLockArguments {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$StateDir)

    $configPath = Join-Path $StateDir "default.json"
    $logPath = Join-Path $StateDir "client.log"
    return @(
        "--service", $script:IwsServiceName,
        "--daemon-addr", $script:IwsDaemonAddress,
        "--config", $configPath,
        "--log-file", $logPath,
        "service", "reconfigure",
        "--disable-profiles",
        "--disable-update-settings",
        "--disable-networks",
        "--service-env", "NB_STATE_DIR=$StateDir"
    )
}

function Get-IwsServiceControlArguments {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet("start", "stop", "status", "uninstall")]
        [string]$Action
    )
    return @(
        "--service", $script:IwsServiceName,
        "--daemon-addr", $script:IwsDaemonAddress,
        "service", $Action
    )
}

function Get-IwsEnrollmentArguments {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][psobject]$Payload)

    return @(
        "--daemon-addr", $script:IwsDaemonAddress,
        "--management-url", [string]$Payload.management_server,
        "--hostname", [string]$Payload.device_name,
        "up",
        "--setup-key-file", [string]$Payload.setup_key_file,
        "--interface-name", $script:IwsInterfaceName,
        "--disable-client-routes",
        "--disable-server-routes",
        "--disable-dns",
        "--disable-ipv6",
        "--block-inbound",
        "--block-lan-access"
    )
}

function Assert-IwsArtifact {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$ExpectedSha256
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required IWS runtime artifact was not found."
    }
    if ($ExpectedSha256 -notmatch "^[0-9a-fA-F]{64}$") {
        throw "Expected artifact hash is invalid."
    }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $ExpectedSha256.ToLowerInvariant()) {
        throw "IWS runtime artifact hash verification failed."
    }
}

function Invoke-IwsNativeSanitized {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $null = & $FilePath @Arguments 2>&1 | Out-String
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0) {
        throw "$FailureMessage Exit code: $exitCode."
    }
}

function Test-IwsNativeSuccess {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $null = & $FilePath @Arguments 2>&1 | Out-String
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    return ($exitCode -eq 0)
}

Export-ModuleMember -Function @(
    "Read-IwsPayload",
    "Get-IwsServiceInstallArguments",
    "Get-IwsServiceLockArguments",
    "Get-IwsServiceControlArguments",
    "Get-IwsEnrollmentArguments",
    "Assert-IwsArtifact",
    "Invoke-IwsNativeSanitized",
    "Test-IwsNativeSuccess"
)
