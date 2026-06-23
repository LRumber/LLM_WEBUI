@echo off
cd /d D:\LM_web\ai-service

"D:\LM_web\ai-service\.venv\Scripts\python.exe" -m uvicorn app.main:app --host 0.0.0.0 --port 8001
