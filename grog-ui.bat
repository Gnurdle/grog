@echo off
rem grog-ui — launch the grog Swing GUI (chat window + bash shell window).
rem Double-click this file, or run it from cmd/PowerShell.
rem Requires the Clojure CLI (clojure.bat) on PATH, and a desktop session.

cd /d "%~dp0"

where clojure >nul 2>nul
if errorlevel 1 (
  echo grog-ui: the 'clojure' command line tool was not found on PATH.
  echo          Install it via https://clojure.org/guides/install_clojure
  pause
  exit /b 1
)

rem --- per-instance log with a size cap -------------------------------------
rem Each instance writes to its own file: base = %GROG_LOG% or ~/.grog-ui.log,
rem suffixed with the process id, so multiple instances don't clobber each other.
rem The file is capped at %GROG_UI_LOG_MAX% bytes (default 5MB) keeping the tail.
if "%GROG_LOG%"=="" (
  set "LOG_BASE=%USERPROFILE%\.grog-ui.log"
) else (
  set "LOG_BASE=%GROG_LOG%"
)
if "%GROG_UI_LOG_MAX%"=="" (
  set "MAX_LOG=5242880"
) else (
  set "MAX_LOG=%GROG_UI_LOG_MAX%"
)
set "LOG=%LOG_BASE:%.log=%.%RANDOM%.log"
set "GROG_LOG=%LOG%"

rem prune the log to the last MAX_LOG bytes (best effort, only on launch)
if exist "%LOG%" powershell -NoProfile -Command "if((Get-Item -LiteralPath '%LOG%').Length -gt %MAX_LOG%){$b=New-Object byte[] %MAX_LOG%;$s=[IO.File]::Open('%LOG%','Open','Read','ReadWrite');$s.Seek(-%MAX_LOG%,[IO.SeekOrigin]::End) | Out-Null;$s.Read($b,0,%MAX_LOG%) | Out-Null;$s.Dispose();[IO.File]::WriteAllBytes('%LOG%',$b)}" 2>nul

echo === grog-ui launch: %date% %time% log=%LOG% ===>> "%LOG%"

clojure -M:gui %* >>"%LOG%" 2>&1
