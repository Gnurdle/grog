<#
prereqs.ps1 - put everything grog needs onto a Windows machine, in one go.

Two callers:
  * the grog installer, right after it lays grog down
  * a person, by hand:   powershell -ExecutionPolicy Bypass -File prereqs.ps1

Safe to re-run, and PASSIVE: a tool that is already installed - by scoop or by
anything else - is never touched. Only what is missing gets installed.

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
  Write-Host 'Install it by hand: https://scoop.sh'
  exit 1
}
Write-Host "scoop: $scoop"

# The shim is called by absolute path: a freshly installed scoop is not on this
# session's PATH. There is deliberately no wrapper taking an $Args parameter -
# that name collides with PowerShell's automatic $args, and an earlier version of
# this script ended up invoking scoop with no arguments at all (scoop's help).

# --- installed? ---------------------------------------------------------------
# Cached `scoop list`, so the question costs one call rather than one per tool.
$script:ScoopList = $null

function Scoop-Has([string]$Name) {
  if ($null -eq $script:ScoopList) { $script:ScoopList = (& $scoop list 2>$null) -join "`n" }
  return [bool]($script:ScoopList -match "(?m)^\s*$([regex]::Escape($Name))\s")
}

function Ensure([string]$App, [string]$Probe) {
  $name = ($App -split '/')[-1]
  # Present if the tool answers, OR scoop already has it (its shims may not be on
  # this session's PATH yet, which is exactly the stale-PATH trap).
  if (($Probe -and (Get-Command $Probe -ErrorAction SilentlyContinue)) -or (Scoop-Has $name)) {
    Write-Host ("  {0,-26} already present" -f $App)
    return
  }
  Write-Host ("  {0,-26} installing" -f $App)
  & $scoop install $App
  $script:ScoopList = $null      # a fresh install invalidates the cache
}

# --- git first - scoop clones buckets with it, and it is grog's bash ----------
Step 'git (also the bash shell grog uses)'
Ensure 'git' 'bash'

# --- buckets ------------------------------------------------------------------
# Before anything is installed FROM them: java/ holds Temurin, extras/ holds
# LibreOffice, scoop-clojure holds babashka.
Step 'buckets'
$buckets = (& $scoop bucket list 2>$null) -join ' '
if ($buckets -notmatch '\bjava\b')      { & $scoop bucket add java }
if ($buckets -notmatch '\bextras\b')    { & $scoop bucket add extras }
if ($buckets -notmatch 'scoop-clojure') { & $scoop bucket add scoop-clojure https://github.com/littleli/scoop-clojure }

# --- required ------------------------------------------------------------------
Step 'Java'
Ensure 'java/temurin-lts-jdk' 'java'

# --- optional ------------------------------------------------------------------
if (-not $Minimal) {
  Step 'optional tools'
  Ensure 'nodejs-lts' 'node'
  Ensure 'babashka' 'bb'
  Ensure 'ripgrep' 'rg'
  Ensure 'jq' 'jq'
  Ensure 'poppler' 'pdftoppm'
  # tesseract-languages is separate on purpose: tesseract ships the OCR engine
  # but no recognition data, and OCR fails without it.
  Ensure 'tesseract' 'tesseract'
  Ensure 'tesseract-languages' ''
  Ensure 'extras/libreoffice' 'soffice'
}

# --- ECA -----------------------------------------------------------------------
Step "ECA $ecaVersion"
$ecaDir = Join-Path $env:LOCALAPPDATA 'eca'
$ecaExe = Join-Path $ecaDir 'eca.exe'
if (Test-Path $ecaExe) {
  Write-Host "  already at $ecaDir"
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
    Write-Host "  ECA -> $ecaDir (added to your PATH)"
  } catch {
    Write-Host "  ECA download failed: $($_.Exception.Message)" -ForegroundColor Yellow
    Write-Host '  Get it by hand from https://github.com/editor-code-assistant/eca/releases'
  }
}

# --- report ---------------------------------------------------------------------
Step 'summary'
foreach ($c in @('git','bash','java','node','bb','eca','rg','jq','tesseract','pdftoppm')) {
  $src = (Get-Command $c -ErrorAction SilentlyContinue).Source
  Write-Host ("  {0,-12} {1}" -f $c, ($(if ($src) { $src } else { 'not found (optional, or not on PATH yet)' })))
}
Write-Host ''
Write-Host 'Open a NEW terminal before starting grog - PATH changes need a new shell.'
