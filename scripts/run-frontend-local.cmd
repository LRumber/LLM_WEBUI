@echo off
cd /d D:\LM_web\frontend

"D:\Program Files\nodejs\npm.cmd" run dev -- --host 0.0.0.0 --port 8505 --strictPort
