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

# NOTE - two things `scoop which` is no good for, learned the hard way:
#   * scoop.cmd does not propagate the exit 2 its .ps1 raises, so its exit code
#     cannot be trusted; and it prints a WARN line instead of a path when it
#     fails, which an earlier version here read as a shim path.
#   * it resolves through Get-Command, so it is blind to anything installed but
#     not yet on THIS shell's PATH - exactly the state during a first run, while
#     scoop is itself changing PATH.
# Scoop's own `list` is the authority instead; it does not depend on PATH.

# Does the tool answer, right now? Exit code AND output, so a stale shim that
# merely prints an error is not a working tool. PATH-bound, so it is proof of
# presence, never proof of absence.
function Tool-Works($Probe, $VersionArgs) {
  if (-not $Probe) { return $false }
  if (-not (Get-Command $Probe -ErrorAction SilentlyContinue)) { return $false }
  try {
    $out = & $Probe @VersionArgs 2>&1 | Out-String
    return ($LASTEXITCODE -eq 0) -and [bool]($out -match '\S')
  } catch { return $false }
}

# --- the one rule: install only what is missing --------------------------------
function Ensure($App, $Probe, $VersionArgs) {
  $name = ($App -split '/')[-1]
  $state = Scoop-Status $name

  # scoop says the last install failed: the directory is there, the app is not.
  if ($state -eq 'failed') {
    Note ("{0,-26} scoop reports a FAILED install - cleaning up" -f $App)
    & $scoop uninstall $App
    $script:ScoopList = $null
    $state = 'absent'
  }

  # Scoop's list is the authority on "installed". It records a real `current`
  # junction, and it does NOT depend on this shell's PATH - which is the whole
  # reason `Get-Command` and `scoop which` are misleading mid-run, while scoop is
  # itself still changing PATH.
  if ($state -eq 'ok') {
    Note ("{0,-26} already present" -f $App)
    return
  }

  # Not in scoop's list, yet the tool answers: installed by something else. Being
  # passive about that is the point of this script.
  if ($Probe -and (Tool-Works $Probe $VersionArgs)) {
    Note ("{0,-26} already present (not from scoop)" -f $App)
    return
  }

  Note ("{0,-26} installing" -f $App)
  & $scoop install $App
  $script:ScoopList = $null

  # Verify the same way: scoop's record, not this shell's PATH.
  $after = Scoop-Status $name
  if ($after -eq 'ok') { Note ("{0,-26} installed ok" -f $App); return }
  if ($Probe -and (Tool-Works $Probe $VersionArgs)) { Note ("{0,-26} installed ok" -f $App); return }

  Note ("{0,-26} NOT WORKING - scoop still says '{1}'" -f $App, $after)
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
# Two independent questions, reported separately, because during a first run they
# disagree: is it INSTALLED (scoop's record), and is it on THIS shell's PATH (it
# will not be - scoop has just changed PATH, and this shell predates that).
$Checks = @(
  @{ Label = 'bash';      App = 'git';             Probe = 'bash' }
  @{ Label = 'java';      App = 'temurin-lts-jdk'; Probe = 'java' }
  @{ Label = 'node';      App = 'nodejs-lts';      Probe = 'node' }
  @{ Label = 'bb';        App = 'babashka';        Probe = 'bb' }
  @{ Label = 'eca';       App = $null;             Probe = 'eca' }
  @{ Label = 'rg';        App = 'ripgrep';         Probe = 'rg' }
  @{ Label = 'jq';        App = 'jq';              Probe = 'jq' }
  @{ Label = 'tesseract'; App = 'tesseract';       Probe = 'tesseract' }
  @{ Label = 'pdftoppm';  App = 'poppler';         Probe = 'pdftoppm' }
  @{ Label = 'soffice';   App = 'libreoffice';     Probe = 'soffice' }
)
foreach ($c in $Checks) {
  $onPath = (Get-Command $c.Probe -ErrorAction SilentlyContinue).Source
  $state = if ($c.App) { Scoop-Status $c.App } else { 'absent' }
  if ($onPath) {
    Note ("{0,-11} {1}" -f $c.Label, $onPath)
  } elseif ($state -eq 'ok') {
    Note ("{0,-11} installed - a NEW shell will have it on PATH" -f $c.Label)
  } elseif ($state -eq 'failed') {
    Note ("{0,-11} scoop reports a FAILED install" -f $c.Label)
  } elseif ($c.Probe -eq 'eca' -and (Test-Path $ecaExe)) {
    Note ("{0,-11} {1}   (a NEW shell will have it on PATH)" -f $c.Label, $ecaExe)
  } else {
    Note ("{0,-11} not found" -f $c.Label)
  }
}

if ($script:Broken.Count -gt 0) {
  Write-Host ''
  Write-Host ('NOT WORKING: ' + ($script:Broken -join ', ')) -ForegroundColor Yellow
  Write-Host 'grog still runs without the optional ones.'
}
Write-Host ''
Write-Host 'Open a NEW terminal before starting grog - PATH changes need a new shell.'
