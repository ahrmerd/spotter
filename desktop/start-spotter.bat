@echo off
cd /d "%~dp0"
where py >nul 2>nul && (py -3 server\app.py & goto :eof)
where python >nul 2>nul && (python server\app.py & goto :eof)
echo Python 3 is not installed. Get it from https://www.python.org/downloads/ then run this file again.
pause
