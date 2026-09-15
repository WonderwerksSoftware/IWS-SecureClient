param(
  [string]$OutputPath = "$PSScriptRoot\IWS-Setup-Template.exe",
  [string]$UninstallerOutputPath = "$PSScriptRoot\IwsUninstall.exe"
)
$csc = "$env:SystemRoot\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
& $csc /nologo /target:winexe /platform:x64 /optimize+ `
  /reference:System.dll /reference:System.Core.dll /reference:System.Windows.Forms.dll `
  /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll `
  /reference:System.Runtime.Serialization.dll /reference:System.ServiceProcess.dll `
  "/win32manifest:$PSScriptRoot\IwsSetupBootstrap.manifest" "/out:$OutputPath" `
  "$PSScriptRoot\IwsSetupDiagnostics.cs" "$PSScriptRoot\IwsSetupState.cs" `
  "$PSScriptRoot\IwsSetupMetadata.cs" `
  "$PSScriptRoot\IwsSetupBootstrap.cs"
if ($LASTEXITCODE -ne 0) { throw "IWS bootstrap compilation failed." }
& $csc /nologo /target:winexe /platform:x64 /optimize+ `
  /reference:System.dll /reference:System.Windows.Forms.dll `
  "/win32manifest:$PSScriptRoot\IwsSetupBootstrap.manifest" "/out:$UninstallerOutputPath" `
  "$PSScriptRoot\IwsUninstall.cs"
if ($LASTEXITCODE -ne 0) { throw "IWS uninstaller compilation failed." }
Get-FileHash -LiteralPath $OutputPath, $UninstallerOutputPath -Algorithm SHA256
