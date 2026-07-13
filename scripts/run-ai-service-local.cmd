@echo off
cd /d D:\LM_web\ai-service

if exist "C:\Program Files\LibreOffice\program\soffice.exe" set "LIBREOFFICE_PATH=C:\Program Files\LibreOffice\program\soffice.exe"
if exist "C:\Program Files (x86)\LibreOffice\program\soffice.exe" set "LIBREOFFICE_PATH=C:\Program Files (x86)\LibreOffice\program\soffice.exe"

"D:\LM_web\ai-service\.venv\Scripts\python.exe" -m uvicorn app.main:app --host 0.0.0.0 --port 8001
