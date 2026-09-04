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

rem --- log rotation ----------------------------------------------------------
rem grog-ui always writes to a single current log file (%GROG_LOG% or
rem %USERPROFILE%\grog-ui.log). On each launch the previous log is rotated:
rem capped to the last MAX_LOG bytes, renamed to <base>.<next>, and only the
rem newest %GROG_UI_LOG_KEEP% rotations are kept (older ones removed) — see
rem scripts/rotate-log.ps1. Set GROG_LOG to redirect to a different base.
if "%GROG_LOG%"=="" (
  set "LOG_BASE=%USERPROFILE%\grog-ui.log"
) else (
  set "LOG_BASE=%GROG_LOG%"
)
if "%GROG_UI_LOG_MAX%"=="" (
  set "MAX_LOG=5242880"
) else (
  set "MAX_LOG=%GROG_UI_LOG_MAX%"
)
if "%GROG_UI_LOG_KEEP%"=="" (
  set "KEEP=5"
) else (
  set "KEEP=%GROG_UI_LOG_KEEP%"
)
set "LOG=%LOG_BASE%"
set "GROG_LOG=%LOG%"

rem rotate the previous log (best effort, only on launch)
if exist "%LOG_BASE%" powershell -NoProfile -File "%~dp0scripts\rotate-log.ps1" "%LOG_BASE%" %MAX_LOG% %KEEP%

echo === grog-ui launch: %date% %time% log=%LOG% ===>> "%LOG%"

clojure -M:gui %* >>"%LOG%" 2>&1
