#Requires -Version 5.1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$output = Join-Path $env:TEMP ("IwsSetupStateTests-" + [Guid]::NewGuid().ToString("N") + ".exe")
$csc = "$env:SystemRoot\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
try {
    & $csc /nologo /target:exe /platform:x64 `
        /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll `
        "/out:$output" `
        (Join-Path $root "IwsSetupDiagnostics.cs") `
        (Join-Path $root "IwsSetupState.cs") `
        (Join-Path $root "IwsSetupMetadata.cs") `
        (Join-Path $root "IwsSetupRecovery.cs") `
        (Join-Path $PSScriptRoot "IwsSetupStateTests.cs")
    if ($LASTEXITCODE -ne 0) { throw "IWS setup state test compilation failed." }
    & $output
    if ($LASTEXITCODE -ne 0) { throw "IWS setup state tests failed." }
}
finally {
    Remove-Item -LiteralPath $output -Force -ErrorAction SilentlyContinue
}
