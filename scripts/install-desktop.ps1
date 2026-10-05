# install-desktop.ps1 -- put grog in the Windows desktop/Start Menu.
#
# Creates per-user shortcuts (Desktop + Start Menu) that launch the client with
# the grog icon — so the app is a real app on the machine, not "find the tree
# and double-click a .bat".
#
# The FULL install (electron-builder / NSIS) creates these itself for a packaged
# build; this script is the dev-tree path and stays useful after `bb dist`
# produces the unpacked tree (repo-root dist/win-unpacked).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\install-desktop.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\install-desktop.ps1 -Uninstall
param([switch]$Uninstall, [string]$ExePath)
$ErrorActionPreference = 'Stop'

$repo   = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$web    = Join-Path $repo 'clients\web'
$target = if ($ExePath) { $ExePath } else { Join-Path $PSScriptRoot 'grog-client.bat' }
# assets\ holds the multi-resolution .ico (256/128/64/48/32/16). There is no
# clients\web\build\ dir — electron-builder's buildResources default is unused
# because every icon is named explicitly in package.json.
$ico    = Join-Path $web 'assets\icon.ico'

$desktop  = [Environment]::GetFolderPath('Desktop')
$startDir = Join-Path ([Environment]::GetFolderPath('StartMenu')) 'Programs'
$links = @(
  (Join-Path $desktop  'grog.lnk'),
  (Join-Path $startDir 'grog.lnk')
)

if ($Uninstall) {
  foreach ($l in $links) { if (Test-Path $l) { Remove-Item $l -Force; Write-Host "removed $l" } }
  Write-Host 'grog shortcuts removed.'
  exit 0
}

if (-not (Test-Path $target)) { throw "launcher not found: $target" }
if (-not (Test-Path $ico))    { Write-Warning "icon not found: $ico (shortcut will use a default icon)" }

$shell = New-Object -ComObject WScript.Shell
foreach ($l in $links) {
  $sc = $shell.CreateShortcut($l)
  $sc.TargetPath        = $target
  $sc.WorkingDirectory  = $repo
  $sc.Description       = 'grog — AI coding assistant with on-machine tools'
  $sc.WindowStyle       = 7          # minimised console while the app runs
  if (Test-Path $ico) { $sc.IconLocation = "$ico,0" }
  $sc.Save()
  Write-Host "installed $l"
}
Write-Host ''
Write-Host 'grog is in the Start Menu and on the Desktop.'
Write-Host "launcher: $target"
Write-Host 'Uninstall: powershell -File scripts\install-desktop.ps1 -Uninstall'
