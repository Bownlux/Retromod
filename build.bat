@echo off
REM Keep one Windows distribution path so release checks cannot drift.

call "%~dp0build-all.bat" %*
exit /b %ERRORLEVEL%
