@echo off
rem Double-click / cmd wrapper for build-apk.ps1. Passes all args through.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-apk.ps1" %*
