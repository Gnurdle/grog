; installer.nsh - electron-builder NSIS customisation for grog.
;
; grog needs three things to run: a Java runtime, a bash shell (Git for
; Windows), and ECA. Instead of sending the user to a documentation page, the
; installer runs scripts/prereqs.ps1 - shipped inside the app at
; resources\setup\prereqs.ps1 - which installs them with scoop.
;
; The script is idempotent, so re-running the installer (or installing on a
; machine that already has them) is quick. For unattended installs, set
; GROG_SKIP_PREREQS=1 and the step is skipped.

!macro customInstall
  ReadEnvStr $0 "GROG_SKIP_PREREQS"
  StrCmp $0 "1" grog_skip_prereqs

  DetailPrint "Installing prerequisites (Java, Git, ECA) - this can take several minutes..."
  DetailPrint "A PowerShell window may open and show progress."

  ; ExecWait, not nsExec, so the user can watch it rather than staring at a
  ; frozen progress bar for ten minutes.
  ExecWait 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\resources\setup\prereqs.ps1"'

  DetailPrint "Prerequisites finished. Open a new terminal before starting grog."

  grog_skip_prereqs:
!macroend

!macro customUnInstall
  ; Nothing to undo: the prerequisites are ordinary per-user tools that other
  ; software may rely on, so they are left in place.
!macroend
