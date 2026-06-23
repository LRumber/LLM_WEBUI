@echo off
cd /d D:\LM_web\backend

for /f "tokens=1,* delims==" %%A in ('findstr /r "^[A-Za-z_][A-Za-z0-9_]*=" ..\.env') do set "%%A=%%B"
set "DEV_LOGIN_ENABLED=true"

for /f "delims=" %%J in ('dir /b /a-d target\lm-platform-backend-*.jar ^| findstr /v /i ".original"') do set "BACKEND_JAR=%%J"

"D:\Program Files\Java\jdk-21\bin\java.exe" -jar "D:\LM_web\backend\target\%BACKEND_JAR%"
