@echo off
setlocal
rem Downloads the latest Noel watch APK from GitHub and installs it on the paired watch.
rem Put this file next to adb.exe (platform-tools). Watch: Developer options -> Wireless debugging ON.

cd /d "%~dp0"
echo Downloading latest szoptatas-wear.apk ...
curl -sSL -o szoptatas-wear.apk https://github.com/Csicsi/Szoptatas/releases/latest/download/szoptatas-wear.apk
if errorlevel 1 (echo Download failed. & pause & exit /b 1)

rem drop stale "offline" entries, then wait for the watch to show up via mDNS
adb reconnect offline >nul 2>&1
echo Looking for the watch (Wireless debugging must be on) ...
set "DEV="
for /l %%i in (1,1,15) do (
  for /f "tokens=1,2" %%a in ('adb devices ^| findstr /e "device"') do if not defined DEV set "DEV=%%a"
  if defined DEV goto found
  timeout /t 2 >nul
)
echo.
echo Watch not found automatically.
echo On the watch open Wireless debugging and read "IP address ^& port" (main screen, NOT "Pair new device").
set /p ADDR=Type it here (e.g. 192.168.0.171:37123): 
adb connect %ADDR%
for /f "tokens=1,2" %%a in ('adb devices ^| findstr /e "device"') do if not defined DEV set "DEV=%%a"
if defined DEV goto found
echo.
echo Still not connected. If the watch was reset or re-paired, first run:
echo    adb pair IP:PAIRPORT CODE      (from "Pair new device" on the watch)
echo then start this script again.
pause & exit /b 1

:found
echo Installing on %DEV% ...
adb -s %DEV% install -r szoptatas-wear.apk
if errorlevel 1 (echo Install failed. & pause & exit /b 1)
echo Done. Turn off Wireless debugging on the watch to save battery.
pause
