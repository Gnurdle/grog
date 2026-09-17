# rotate-log.ps1 <base> [maxBytes] [keep]
#
# grog-ui always writes to <base> — the single "current" log. Before a fresh
# launch we rotate: if <base> already exists we
#   1. cap it to the most recent <maxBytes> bytes (best effort, only on launch),
#   2. rename it to <base>.<next> where <next> is the first free integer suffix,
#   3. delete all but the <keep> most recent rotations
# so the freshest log is always <base> and we don't accumulate files forever.
#
# Arguments (all optional when env already set by the caller):
#   <base>     full path of the current log, e.g. C:\Users\you\grog-ui.log
#   <maxBytes> per-file size cap (default 5242880 = 5MB)
#   <keep>     how many rotations to keep (default 5)
param(
  [string]$base,
  [int]   $maxBytes = 5242880,
  [int]   $keep     = 5
)

if ($base -eq "") { $base = Join-Path $env:USERPROFILE "grog-ui.log" }

# nothing to rotate yet — the caller will create <base> fresh
if (-not (Test-Path $base)) { exit 0 }

# --- 1. cap the current file to the most recent $maxBytes bytes -------------
try {
  $size = (Get-Item -LiteralPath $base).Length
  if ($size -gt $maxBytes) {
    $buf = New-Object byte[] $maxBytes
    $s = [IO.File]::Open($base, "Open", "Read", "ReadWrite")
    $s.Seek(-$maxBytes, [IO.SeekOrigin]::End) | Out-Null
    $s.Read($buf, 0, $maxBytes) | Out-Null
    $s.Dispose()
    [IO.File]::WriteAllBytes($base, $buf)
  }
} catch {
  # best effort only — never fail the launch because of rotation
}

# --- 2. enumerate rotations as <number,path> pairs ---------------------------
# (built explicitly so a single rotation isn't silently flattened to a scalar)
$rots = [System.Collections.ArrayList]::new()
Get-ChildItem "$base.*" | ForEach-Object {
  if ($_.Name -match "\.(\d+)$") {
    $null = $rots.Add(@([int]$Matches[1], $_.FullName))
  }
}

# sort numerically descending (newest first); number is element 0
$sorted = $rots.ToArray() | Sort-Object -Property { $_[0] } -Descending

# --- 3. rename current -> <base>.maxN+1 --------------------------------------
$maxN = 0
foreach ($pair in $sorted) { if ($pair[0] -gt $maxN) { $maxN = $pair[0] } }
Move-Item -LiteralPath $base "$base.$($maxN + 1)"

# --- 4. delete all but the $keep most recent rotations -----------------------
$i = 0
foreach ($pair in $sorted) {
  $i = $i + 1
  if ($i -gt $keep) { Remove-Item -Force $pair[1] }
}
exit 0