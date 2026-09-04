Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:BoundaryGroup = "IWS Client Boundary POC"
$script:ApprovedAddress = "100.83.246.85"
$script:ApprovedPort = 443
$script:LowerRange = "0.0.0.0-100.83.246.84"
$script:UpperRange = "100.83.246.86-255.255.255.255"
$script:IPv6Range = "0:0:0:0:0:0:0:0-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"

function New-IwsBoundarySpec {
    param(
        [string]$Name,
        [string]$Program,
        [string]$Action,
        [string]$Protocol,
        [string]$RemoteAddress,
        [string[]]$RemotePort
    )
    [pscustomobject]@{
        Name = $Name
        Program = $Program
        Action = $Action
        Protocol = $Protocol
        RemoteAddress = $RemoteAddress
        RemotePort = @($RemotePort)
    }
}

function Get-IwsBoundaryRuleSpecs {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string[]]$ProgramPaths)

    $index = 0
    foreach ($program in $ProgramPaths) {
        $index += 1
        New-IwsBoundarySpec "P$index allow IWS TCP 443" $program "Allow" "TCP" $script:ApprovedAddress @("443")
        New-IwsBoundarySpec "P$index block lower IPv4 TCP" $program "Block" "TCP" $script:LowerRange @("0-65535")
        New-IwsBoundarySpec "P$index block upper IPv4 TCP" $program "Block" "TCP" $script:UpperRange @("0-65535")
        New-IwsBoundarySpec "P$index block IWS other TCP" $program "Block" "TCP" $script:ApprovedAddress @("0-442", "444-65535")
        New-IwsBoundarySpec "P$index block lower IPv4 UDP" $program "Block" "UDP" $script:LowerRange @("0-65535")
        New-IwsBoundarySpec "P$index block IWS UDP" $program "Block" "UDP" $script:ApprovedAddress @("0-65535")
        New-IwsBoundarySpec "P$index block upper IPv4 UDP" $program "Block" "UDP" $script:UpperRange @("0-65535")
        New-IwsBoundarySpec "P$index block lower IPv4 ICMP" $program "Block" "ICMPv4" $script:LowerRange @()
        New-IwsBoundarySpec "P$index block IWS ICMP" $program "Block" "ICMPv4" $script:ApprovedAddress @()
        New-IwsBoundarySpec "P$index block upper IPv4 ICMP" $program "Block" "ICMPv4" $script:UpperRange @()
        New-IwsBoundarySpec "P$index block IPv6 TCP" $program "Block" "TCP" $script:IPv6Range @("0-65535")
        New-IwsBoundarySpec "P$index block IPv6 UDP" $program "Block" "UDP" $script:IPv6Range @("0-65535")
        New-IwsBoundarySpec "P$index block IPv6 ICMP" $program "Block" "ICMPv6" $script:IPv6Range @()
    }
}

function ConvertTo-IwsIPv4Number {
    param([string]$Address)
    $bytes = [Net.IPAddress]::Parse($Address).GetAddressBytes()
    if ($bytes.Length -ne 4) {
        throw "Expected an IPv4 address."
    }
    return ([uint64]$bytes[0] -shl 24) -bor
        ([uint64]$bytes[1] -shl 16) -bor
        ([uint64]$bytes[2] -shl 8) -bor
        [uint64]$bytes[3]
}

function Test-IwsAddressContainsApproved {
    param([string]$AddressSpec)
    if ($AddressSpec -eq $script:ApprovedAddress) {
        return $true
    }
    if ($AddressSpec -notmatch '^([0-9.]+)-([0-9.]+)$') {
        return $false
    }
    $approved = ConvertTo-IwsIPv4Number $script:ApprovedAddress
    return $approved -ge (ConvertTo-IwsIPv4Number $matches[1]) -and
        $approved -le (ConvertTo-IwsIPv4Number $matches[2])
}

function Test-IwsPortsContainApproved {
    param([string[]]$PortSpecs)
    foreach ($portSpec in $PortSpecs) {
        if ($portSpec -eq "Any" -or $portSpec -eq [string]$script:ApprovedPort) {
            return $true
        }
        if ($portSpec -match '^(\d+)-(\d+)$' -and
            $script:ApprovedPort -ge [int]$matches[1] -and
            $script:ApprovedPort -le [int]$matches[2]) {
            return $true
        }
    }
    return $false
}

function Test-IwsBoundaryRuleSpecs {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][object[]]$RuleSpecs)

    $programs = @($RuleSpecs.Program | Select-Object -Unique)
    foreach ($program in $programs) {
        $programRules = @($RuleSpecs | Where-Object Program -eq $program)
        $allows = @($programRules | Where-Object {
            $_.Action -eq "Allow" -and $_.Protocol -eq "TCP" -and
            $_.RemoteAddress -eq $script:ApprovedAddress -and
            @($_.RemotePort) -contains [string]$script:ApprovedPort
        })
        if ($allows.Count -ne 1) {
            throw "Each IWS program requires exactly one approved allow tuple."
        }
        foreach ($rule in $programRules) {
            if ($rule.RemoteAddress -in @("Any", "0.0.0.0/0") -or
                ($rule.Action -eq "Block" -and $rule.Protocol -eq "Any")) {
                throw "Wildcard firewall blocks are prohibited."
            }
            if ($rule.Action -eq "Block" -and $rule.Protocol -eq "TCP" -and
                (Test-IwsAddressContainsApproved $rule.RemoteAddress) -and
                (Test-IwsPortsContainApproved @($rule.RemotePort))) {
                throw "A block rule overlaps the approved IWS TCP tuple."
            }
        }
    }
    return $true
}

Export-ModuleMember -Function @(
    "Get-IwsBoundaryRuleSpecs",
    "Test-IwsBoundaryRuleSpecs"
)
