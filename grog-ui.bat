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

clojure -M:gui %*
