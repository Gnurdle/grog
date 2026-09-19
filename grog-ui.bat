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

rem --- logging ---------------------------------------------------------------
rem Handled in-process by grog itself (grog.log): each instance writes its own
rem <base>.<pid>.log and prunes the oldest. No redirection or PID lookup here —
rem the JVM tees its own stdout/stderr. Set GROG_LOG to change the log base
rem (default %USERPROFILE%\grog-ui); GROG_UI_LOG_KEEP to change how many
rem instance logs are kept (default 5).

clojure -M:gui %*
