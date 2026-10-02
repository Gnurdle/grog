<#
prereqs.ps1 - put everything grog needs onto a Windows machine, in one go.

Two callers:
  * the grog installer, right after it lays grog down
  * a person, by hand:   powershell -ExecutionPolicy Bypass -File prereqs.ps1

Idempotent - re-running skips whatever is already present.

  -Minimal   required pieces only (git/bash, Java, ECA); skip the optional tools
#>
[CmdletBinding()]
param([switch]$Minimal)

$ErrorActionPreference = 'Continue'
$ecaVersion = '0.134.2'

function Step($m) { Write-Host ''; Write-Host "== $m" -ForegroundColor Cyan }

# --- scoop -------------------------------------------------------------------
Step 'scoop'
$scoop = Join-Path $env:USERPROFILE 'scoop\shims\scoop.cmd'
if (-not (Test-Path $scoop)) {
  try { Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser -Force } catch {}
  Invoke-RestMethod -Uri 'https://get.scoop.sh' | Invoke-Expression
}
if (-not (Test-Path $scoop)) {
  Write-Host 'scoop could not be installed - stopping' -ForegroundColor Red
  exit 1
}
Write-Host "scoop: $scoop"

# Call the shim by path: a freshly installed scoop is not on this session's PATH.
function Scoop([string[]]$Args) { & $scoop @Args }

# --- git first - scoop clones buckets with it, and it is grog's bash ----------
Step 'git (also the bash shell grog uses)'
Scoop @('install','git')

# --- buckets -----------------------------------------------------------------
Step 'buckets'
$buckets = (& $scoop bucket list 2>$null) -join ' '
if ($buckets -notmatch '\bjava\b')         { Scoop @('bucket','add','java') }
if ($buckets -notmatch '\bextras\b')       { Scoop @('bucket','add','extras') }
if ($buckets -notmatch 'scoop-clojure')    { Scoop @('bucket','add','scoop-clojure','https://github.com/littleli/scoop-clojure') }

# --- required ------------------------------------------------------------------
Step 'Java'
Scoop @('install','java/temurin-lts-jdk')

# --- optional ------------------------------------------------------------------
if (-not $Minimal) {
  Step 'optional tools'
  # tesseract-languages is separate on purpose: tesseract ships the OCR engine
  # but no recognition data, and OCR fails without it.
  Scoop @('install','nodejs-lts')
  Scoop @('install','babashka')
  Scoop @('install','ripgrep','jq')
  Scoop @('install','poppler','tesseract','tesseract-languages')
  Scoop @('install','extras/libreoffice')
}

# --- ECA -----------------------------------------------------------------------
Step "ECA $ecaVersion"
$ecaDir = Join-Path $env:LOCALAPPDATA 'eca'
$ecaExe = Join-Path $ecaDir 'eca.exe'
if (Test-Path $ecaExe) {
  Write-Host "already at $ecaDir"
} else {
  $zip = Join-Path $env:TEMP 'eca-native-windows-amd64.zip'
  try {
    $url = "https://github.com/editor-code-assistant/eca/releases/download/$ecaVersion/eca-native-windows-amd64.zip"
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip
    New-Item -ItemType Directory -Force -Path $ecaDir | Out-Null
    Expand-Archive -Path $zip -DestinationPath $ecaDir -Force
    # the archive may nest the binary; hoist it
    $nested = Get-ChildItem $ecaDir -Recurse -Filter eca.exe | Select-Object -First 1
    if ($nested -and $nested.DirectoryName -ne $ecaDir) { Copy-Item $nested.FullName $ecaExe -Force }
    $userPath = [Environment]::GetEnvironmentVariable('Path','User')
    if ($userPath -notlike "*$ecaDir*") {
      [Environment]::SetEnvironmentVariable('Path', ($userPath.TrimEnd(';') + ';' + $ecaDir), 'User')
    }
    Write-Host "ECA -> $ecaDir (added to your PATH)"
  } catch {
    Write-Host "ECA download failed: $($_.Exception.Message)" -ForegroundColor Yellow
    Write-Host "Get it by hand from https://github.com/editor-code-assistant/eca/releases"
  }
}

# --- report ---------------------------------------------------------------------
Step 'summary'
foreach ($c in @('git','bash','java','node','bb','eca','rg','jq','tesseract','pdftoppm')) {
  $src = (Get-Command $c -ErrorAction SilentlyContinue).Source
  Write-Host ("  {0,-12} {1}" -f $c, ($(if ($src) { $src } else { 'MISSING' })))
}
Write-Host ''
Write-Host 'Open a NEW terminal before starting grog - PATH changes need a new shell.'
