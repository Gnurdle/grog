<#
prereqs.ps1 - put everything grog needs onto a Windows machine, in one go.

Two callers:
  * the grog installer, right after it lays grog down
  * a person, by hand:   powershell -ExecutionPolicy Bypass -File prereqs.ps1

Safe to re-run and PASSIVE: anything already usable - installed by scoop or by
anything else - is left alone.

"Already usable" is decided with scoop's own signals rather than by guessing:

  scoop list          installed apps. A failed install appears as a row whose
                      Info column reads "Install failed"; the app directory
                      exists but has no `current` junction.
  scoop which <cmd>   resolves the shim's TARGET. Exit 2 means "not found, not
                      a scoop shim, or a broken shim" - so a leftover shim from
                      a failed install is not mistaken for a working tool.
  and, of course,      actually running the tool, for anything scoop did not
                      install.

  -Minimal   required pieces only (git/bash, Java, ECA); skip the optional tools
#>
[CmdletBinding()]
param([switch]$Minimal)

$ErrorActionPreference = 'Continue'
$ecaVersion = '0.134.2'

function Step($m) { Write-Host ''; Write-Host "== $m" -ForegroundColor Cyan }
function Note($m) { Write-Host ("  " + $m) }

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
Note "scoop: $scoop"

# The shim is called by absolute path: a freshly installed scoop is not on this
# session's PATH. There is deliberately no wrapper taking an $Args parameter -
# that name collides with PowerShell's automatic $args, and an earlier version of
# this script ended up invoking scoop with no arguments at all (scoop's help).

# --- scoop queries --------------------------------------------------------------
$script:ScoopList = $null
$script:Broken = @()

function Scoop-Rows {
  if ($null -eq $script:ScoopList) { $script:ScoopList = @(& $scoop list 2>$null) }
  return $script:ScoopList
}

# 'ok' | 'failed' | 'absent' - straight from what scoop reports.
function Scoop-Status([string]$Name) {
  $row = Scoop-Rows | Where-Object { $_ -match "^\s*$([regex]::Escape($Name))\s" } | Select-Object -First 1
  if (-not $row) { return 'absent' }
  if ($row -match 'Install failed') { return 'failed' }
  return 'ok'
}

# True when scoop's shim resolves to a real target. Exit 2 covers a broken shim.
function Scoop-Which([string]$Cmd) {
  if (-not $Cmd) { return $false }
  & $scoop which $Cmd *> $null
  return ($LASTEXITCODE -eq 0)
}

# Does the tool actually answer? Exit code AND output, so a stale shim that
# prints an error is not counted as a working tool.
function Tool-Works($Probe, $VersionArgs) {
  if (-not $Probe) { return $false }
  if (-not (Get-Command $Probe -ErrorAction SilentlyContinue)) { return $false }
  try {
    $out = & $Probe @VersionArgs 2>&1 | Out-String
    return ($LASTEXITCODE -eq 0) -and [bool]($out -match '\S')
  } catch { return $false }
}

# A tool is usable when EITHER
#   * scoop has it properly (its list says ok, not failed) and its shim resolves
#     to a real target - the cheap path, no cold-start of the program, OR
#   * it runs, whoever installed it (a Java from elsewhere is left alone).
# `scoop which` on its own is NOT enough: it can resolve a stale shim for an app
# scoop has already written off - which is exactly how a cleaned-up LibreOffice
# managed to be reported "already present" without being reinstalled.
function Usable($Name, $Probe, $VersionArgs) {
  if (-not $Probe) { return $false }
  if ((Scoop-Status $Name) -eq 'ok' -and (Scoop-Which $Probe)) { return $true }
  return (Tool-Works $Probe $VersionArgs)
}

# --- the one rule: install only what is missing --------------------------------
function Ensure($App, $Probe, $VersionArgs) {
  $name = ($App -split '/')[-1]
  $state = Scoop-Status $name
  $mustInstall = $false

  # scoop says the last install failed: the directory is there, the app is not.
  if ($state -eq 'failed') {
    Note ("{0,-26} scoop reports a FAILED install - cleaning up" -f $App)
    & $scoop uninstall $App
    $script:ScoopList = $null
    # Cleaned up means GONE: installing is not optional now, and no shim check is
    # allowed to talk us out of it.
    $state = 'absent'
    $mustInstall = $true
  }

  if (-not $mustInstall) {
    if (-not $Probe) {
      # No program to run (language data). Scoop's word is all there is.
      if ($state -eq 'ok') { Note ("{0,-26} installed (no version check available)" -f $App); return }
    } elseif (Usable $name $Probe $VersionArgs) {
      Note ("{0,-26} already present" -f $App)
      return
    }

    # Registered, but nothing answers: usually stale shims from a moved install.
    if ($state -eq 'ok') {
      Note ("{0,-26} installed but not answering - resetting shims" -f $App)
      & $scoop reset $App
      if (Usable $name $Probe $VersionArgs) { Note ("{0,-26} ok after reset" -f $App); return }
    }
  }

  Note ("{0,-26} installing" -f $App)
  & $scoop install $App
  $script:ScoopList = $null

  if (-not $Probe) { Note ("{0,-26} installed (no version check available)" -f $App); return }
  if (Usable $name $Probe $VersionArgs) { Note ("{0,-26} installed ok" -f $App); return }

  Note ("{0,-26} INSTALLED BUT NOT WORKING - check it by hand" -f $App)
  $script:Broken += $App
}

# --- git first - scoop clones buckets with it, and it is grog's bash ----------
Step 'git (also the bash shell grog uses)'
Ensure 'git' 'bash' @('--version')

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
Ensure 'java/temurin-lts-jdk' 'java' @('-version')

# --- optional ------------------------------------------------------------------
if (-not $Minimal) {
  Step 'optional tools'
  Ensure 'nodejs-lts'         'node'      @('--version')
  Ensure 'babashka'           'bb'        @('--version')
  Ensure 'ripgrep'            'rg'        @('--version')
  Ensure 'jq'                 'jq'        @('--version')
  Ensure 'poppler'            'pdftoppm'  @('-v')
  Ensure 'tesseract'          'tesseract' @('--version')
  # tesseract-languages is separate on purpose: tesseract ships the OCR engine
  # but no recognition data, and OCR fails without it.
  Ensure 'tesseract-languages' $null      @()
  Ensure 'extras/libreoffice' 'soffice'   @('--version')
}

# --- ECA -----------------------------------------------------------------------
Step "ECA $ecaVersion"
$ecaDir = Join-Path $env:LOCALAPPDATA 'eca'
$ecaExe = Join-Path $ecaDir 'eca.exe'
if (Tool-Works 'eca' @('--version')) {
  Note "already answering: $((Get-Command eca).Source)"
} elseif (Test-Path $ecaExe) {
  Note "already at $ecaDir"
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
    if (Tool-Works $ecaExe @('--version')) { Note "ECA -> $ecaDir (added to your PATH)" }
    else { Note "ECA unpacked but not answering - check $ecaDir"; $script:Broken += 'eca' }
  } catch {
    Note "ECA download failed: $($_.Exception.Message)"
    Note 'Get it by hand from https://github.com/editor-code-assistant/eca/releases'
    $script:Broken += 'eca'
  }
}

# --- report ---------------------------------------------------------------------
Step 'summary'
# Deliberately does NOT judge by this shell's PATH alone: scoop may have changed
# PATH since this session started, so a tool it just installed would look
# missing. Scoop's own view is reported alongside.
foreach ($c in @('bash','java','node','bb','eca','rg','jq','tesseract','pdftoppm','soffice')) {
  $onPath = (Get-Command $c -ErrorAction SilentlyContinue).Source
  if ($onPath) {
    Note ("{0,-12} {1}" -f $c, $onPath)
  } elseif (Scoop-Which $c) {
    $shim = & $scoop which $c 2>$null | Select-Object -First 1
    Note ("{0,-12} {1}   (not on this shell's PATH yet)" -f $c, $shim)
  } elseif ($c -eq 'eca' -and (Test-Path (Join-Path $env:LOCALAPPDATA 'eca\eca.exe'))) {
    Note ("{0,-12} {1}   (not on this shell's PATH yet)" -f $c, (Join-Path $env:LOCALAPPDATA 'eca\eca.exe'))
  } else {
    Note ("{0,-12} not found" -f $c)
  }
}

if ($script:Broken.Count -gt 0) {
  Write-Host ''
  Write-Host ('NOT WORKING: ' + ($script:Broken -join ', ')) -ForegroundColor Yellow
  Write-Host 'grog still runs without the optional ones.'
}
Write-Host ''
Write-Host 'Open a NEW terminal before starting grog - PATH changes need a new shell.'
