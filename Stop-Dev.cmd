@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0scripts\Start-Dev.ps1" -Action Stop
set "RESULT=%ERRORLEVEL%"
pause
exit /b %RESULT%
