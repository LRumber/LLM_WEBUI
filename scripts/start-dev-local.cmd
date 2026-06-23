@echo off
setlocal
cd /d D:\LM_web

if not exist tmp\logs mkdir tmp\logs

start "LM Backend 8080" cmd /k "D:\LM_web\scripts\run-backend-local.cmd"
powershell -NoProfile -Command "Start-Sleep -Seconds 12"
start "LM Frontend 8505" cmd /k "D:\LM_web\scripts\run-frontend-local.cmd"

echo Backend:  http://127.0.0.1:8080
echo Frontend: http://127.0.0.1:8505
