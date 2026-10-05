@echo off
rem grog-client.bat -- launch the grog Electron client from a dev tree (Windows).
rem
rem The Windows twin of scripts/grog-client. Prefers a packaged build, falls back
rem to clients\web. This is what the Desktop / Start Menu shortcut
rem (scripts\install-desktop.ps1) points at.
rem
rem WHY THE LOG NAME IS RANDOM: a leftover process can hold a fixed log file
rem open for writing; cmd then CANNOT set up `> file` / `>> file`, and when a
rem redirection fails cmd does NOT run the command -- so Electron never launches
rem and the window "closes instantly". A fresh name per launch makes that
rem impossible. (Learned the hard way.)
setlocal
set "ROOT=%~dp0.."
set "WEB=%ROOT%\clients\web"
set "PLAYER=%TEMP%\grog-client-%RANDOM%.log"

rem The spine is spawned via `java`, and ECA spawns each MCP server via
rem `bash -lc`, so BOTH must resolve. A desktop launcher is handed a minimal
rem PATH, so name the usual install locations explicitly (nonexistent entries
rem are harmless): scoop first (see doc/windows-quick-start.md), then an
rem installer-managed C:\tools tree.
set "PATH=%USERPROFILE%\scoop\shims;%USERPROFILE%\scoop\apps\nodejs\current;%USERPROFILE%\scoop\apps\temurin-lts-jdk\current\bin;%USERPROFILE%\scoop\apps\git\current\bin;%PATH%"
set "PATH=%PATH%;C:\tools\jdk21\bin;C:\tools\node;C:\tools\bb;C:\tools\eca;C:\Program Files\Git\bin"
set "GROG_DISABLE_GPU=1"
set "NODE_ENV=production"
set "GROG_MCP_BASE_PORT=9800"

rem packaged build wins — electron-builder now writes to the repo-root dist/
rem (clients/web/package.json sets directories.output = ../../dist)
for %%D in ("%ROOT%\dist\win-unpacked") do if exist "%%~fD\grog.exe" (
  echo Launching packaged grog...
  "%%~fD\grog.exe" >>"%PLAYER%" 2>&1
  goto done
)

where node >nul 2>nul
if errorlevel 1 ( echo node is not on PATH -- run: scoop install nodejs & pause & exit /b 1 )
if not exist "%WEB%\node_modules\electron\dist\electron.exe" ( echo Electron missing -- cd clients\web and run: npm install & pause & exit /b 1 )
if not exist "%WEB%\resources\public\js\main.js" ( echo Renderer bundle missing -- cd clients\web and run: npm run build & pause & exit /b 1 )

echo Launching grog...
"%WEB%\node_modules\electron\dist\electron.exe" "%WEB%" >>"%PLAYER%" 2>&1

:done
set "RC=%ERRORLEVEL%"
echo --- log: %PLAYER% ---
if not "%RC%"=="0" ( echo client exited with code %RC% & powershell -NoProfile -Command "Get-Content '%PLAYER%' -Tail 40" & pause )
