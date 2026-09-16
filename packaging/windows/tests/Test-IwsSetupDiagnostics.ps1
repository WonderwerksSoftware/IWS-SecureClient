#Requires -Version 5.1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$output = Join-Path $env:TEMP ("IwsSetupDiagnosticsTests-" + [Guid]::NewGuid().ToString("N") + ".exe")
$csc = "$env:SystemRoot\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
try {
    & $csc /nologo /target:exe /platform:x64 "/out:$output" `
        (Join-Path $root "IwsSetupDiagnostics.cs") `
        (Join-Path $root "IwsSetupState.cs") `
        (Join-Path $PSScriptRoot "IwsSetupDiagnosticsTests.cs")
    if ($LASTEXITCODE -ne 0) { throw "IWS diagnostics test compilation failed." }
    & $output
    if ($LASTEXITCODE -ne 0) { throw "IWS diagnostics tests failed." }
}
finally {
    Remove-Item -LiteralPath $output -Force -ErrorAction SilentlyContinue
}
