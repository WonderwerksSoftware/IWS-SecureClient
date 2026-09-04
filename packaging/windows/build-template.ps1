param([string]$OutputPath = "$PSScriptRoot\IWS-Setup-Template.exe")
$csc = "$env:SystemRoot\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
& $csc /nologo /target:winexe /platform:x64 /optimize+ `
  /reference:System.dll /reference:System.Core.dll /reference:System.Windows.Forms.dll `
  /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll `
  "/win32manifest:$PSScriptRoot\IwsSetupBootstrap.manifest" "/out:$OutputPath" `
  "$PSScriptRoot\IwsSetupBootstrap.cs"
if ($LASTEXITCODE -ne 0) { throw "IWS bootstrap compilation failed." }
Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256
