@echo off
setlocal
set "OUT=%~dp0log_result.txt"
set "VER=%~dp0version_info.txt"
set "TRACE=%~dp0run_trace.txt"
echo %DATE% %TIME% start > "%TRACE%"

echo ============================================================
echo    Qiangge KernelSU  -  Log Collector
echo ============================================================
echo.

adb version >nul 2>nul
if errorlevel 1 goto NOADB
echo %DATE% %TIME% adb-ok >> "%TRACE%"

echo [1/4] dumping app versions ...
echo ---- manager com.qiangge.ksu ----
adb shell "dumpsys package com.qiangge.ksu | grep -i versionName" >> "%VER%" 2>&1
echo ---- phone com.qiangge.phone ----
adb shell "dumpsys package com.qiangge.phone | grep -i versionName" >> "%VER%" 2>&1
echo.
type "%VER%"
echo.
echo   ^(also saved in version_info.txt^)
echo.

echo [2/4] clearing old logs ...
adb logcat -c >> "%TRACE%" 2>&1
adb logcat -b crash -c >> "%TRACE%" 2>&1

echo [3/4] DO THIS ON THE PHONE NOW:
echo         1^) open the "Phone" app
echo         2^) type    626725
echo         3^) tap the "Secret" button
echo.
echo   Then come back to this window and press any key.
echo.
pause >nul
echo %DATE% %TIME% user-done >> "%TRACE%"

echo [4/4] collecting logs ...
> "%OUT%" echo == A. stealth related ==
>> "%OUT%" adb logcat -s Qiangge-stealth -d -v time 2>&1
>> "%OUT%" echo.
>> "%OUT%" echo == B. crash buffer ==
>> "%OUT%" adb logcat -b crash -d -v time 2>&1
>> "%OUT%" echo.
>> "%OUT%" echo == C. full logcat ==
>> "%OUT%" adb logcat -b main,system,crash -d -v time 2>&1

echo.
echo  DONE. Two files were created next to this script:
echo    version_info.txt   - installed versions
echo    log_result.txt     - the log
echo  Send those two files back.
echo.
pause
exit /b 0

:NOADB
echo.
echo  [ERROR] adb was not found.
echo  Put this .bat in the same folder as adb.exe and run it again.
echo.
pause
exit /b 1
