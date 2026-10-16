@echo off
chcp 936 >nul
title ǿ��KSUһ�����
setlocal enabledelayedexpansion

set "DIR=%~dp0"
set "RPT=%DIR%ǿ����ϱ���.txt"
set "DMP=%DIR%�ں���־dmesg.txt"
set "T1=%TEMP%\qg1.txt"
set "T2=%TEMP%\qg2.txt"
set "T3=%TEMP%\qg3.txt"
set "T4=%TEMP%\qg4.txt"
set "T5=%TEMP%\qg5.txt"

del "%RPT%" >nul 2>&1
del "%DMP%" >nul 2>&1
del "%T1%" "%T2%" "%T3%" "%T4%" "%T5%" >nul 2>&1

echo ================================================================
echo   ǿ�� KSU һ�����  (Windows 10)
echo ----------------------------------------------------------------
echo   1) ƽ���������������ԣ�ƽ��˴� USB ���ԣ�
echo   2) ���б��ű�����;ƽ����������Ȩ root����� ������
echo   3) ������������ļ��������Զ�������
echo        ǿ����ϱ���.txt
echo        �ں���־dmesg.txt
echo ================================================================
echo.

rem ------------------- �� adb -------------------
set "ADB="
where adb.exe >nul 2>&1
if not errorlevel 1 set "ADB=adb.exe"
if not defined ADB if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB if exist "C:\platform-tools\adb.exe" set "ADB=C:\platform-tools\adb.exe"
if not defined ADB if exist "C:\adb\adb.exe" set "ADB=C:\adb\adb.exe"
if not defined ADB if exist "%DIR%adb.exe" set "ADB=%DIR%adb.exe"
if not defined ADB if exist "%DIR%platform-tools\adb.exe" set "ADB=%DIR%platform-tools\adb.exe"
if not defined ADB (
  echo [����] û���ҵ� adb.exe
  echo   �취һ���� adb.exe �ŵ����ű�ͬһ���ļ��У�������һ�Ρ�
  echo   �취�����ü��±��򿪱��ű������������иĳ���� adb.exe ����·����
  echo            set "ADB=C:\���·��\adb.exe"
  echo.
  pause
  exit /b 1
)
echo [adb] %ADB%
echo.

rem ------------------- ���Ӽ�� -------------------
echo ---------- �豸���� ----------
"%ADB%" devices >"%T1%" 2>&1
type "%T1%"
findstr /i /c:"unauthorized" /c:"offline" "%T1%" >nul 2>&1
if not errorlevel 1 (
  echo.
  echo [��ʾ] �豸��ʾ unauthorized / offline������ƽ�������� USB ���Ժ����ܱ��ű���
)
echo.

>"%RPT%" echo ǿ��KSU��ϱ���
>>"%RPT%" echo [adb] %ADB%
>>"%RPT%" echo ===== �豸���� =====
type "%T1%" >>"%RPT%"
>>"%RPT%" echo.

rem ------------------- [1/9] �豸��ϵͳ -------------------
echo ---------- [1/9] �豸��ϵͳ ----------
>>"%RPT%" echo ===== [1/9] �豸��ϵͳ =====
echo �ͺ�:
"%ADB%" shell "getprop ro.product.model" >"%T1%" 2>&1
type "%T1%"
type "%T1%" >>"%RPT%"
echo ��׿�汾:
"%ADB%" shell "getprop ro.build.version.release" >"%T1%" 2>&1
type "%T1%"
type "%T1%" >>"%RPT%"
echo ϵͳ�汾��:
"%ADB%" shell "getprop ro.build.display.id" >"%T1%" 2>&1
type "%T1%"
type "%T1%" >>"%RPT%"
echo.

rem ------------------- [2/9] �ں� -------------------
echo ---------- [2/9] �ں� ----------
>>"%RPT%" echo ===== [2/9] �ں� =====
echo uname -r:
"%ADB%" shell "uname -r" >"%T1%" 2>&1
type "%T1%"
type "%T1%" >>"%RPT%"
echo /proc/version:
"%ADB%" shell "cat /proc/version" >"%T1%" 2>&1
type "%T1%"
type "%T1%" >>"%RPT%"
echo.

rem ------------------- [3/9] ���������� -------------------
echo ---------- [3/9] ���������� ----------
>>"%RPT%" echo ===== [3/9] ���������� =====
echo slot_suffix ��ǰ���:
"%ADB%" shell "getprop ro.boot.slot_suffix" >"%T1%" 2>&1
type "%T1%"
type "%T1%" >>"%RPT%"
echo flash.locked 1=��������:
"%ADB%" shell "getprop ro.boot.flash.locked" >"%T1%" 2>&1
type "%T1%"
type "%T1%" >>"%RPT%"
echo verifiedbootstate:
"%ADB%" shell "getprop ro.boot.verifiedbootstate" >"%T1%" 2>&1
type "%T1%"
type "%T1%" >>"%RPT%"
echo.

rem ------------------- [4/9] ��Ŀ¼ ramdisk �ؼ��� -------------------
echo ---------- [4/9] ��Ŀ¼ ramdisk �ؼ��� ----------
>>"%RPT%" echo ===== [4/9] ��Ŀ¼ ramdisk =====
"%ADB%" shell "ls -la /" >"%T4%" 2>&1
type "%T4%" >>"%RPT%"
echo ---- �ؼ���Ŀ sevenk / kernelsu / init.real ----
set "HASRAM="
findstr /i /c:"sevenk.ko" /c:"kernelsu.ko" /c:"init.real" "%T4%" >"%T2%" 2>nul
findstr /i /c:"sevenk.ko" /c:"kernelsu.ko" /c:"init.real" "%T4%" >nul 2>&1
if not errorlevel 1 set "HASRAM=1"
type "%T2%"
if defined HASRAM (
  echo   ��������Щ = ǿ�粹���Ѿ�����
) else (
  echo   û�ҵ� sevenk.ko / kernelsu.ko / init.real
)
>>"%RPT%" echo ---- �ؼ���Ŀ ----
type "%T2%" >>"%RPT%"
echo ---- /init ��һ�� ----
findstr /i /c:" init" "%T4%"
echo.

echo.
echo *** ���漸����Ҫ root��ƽ����������Ȩ������㡾������***
echo.

rem ------------------- [5/9] root ̽�� -------------------
echo ---------- [5/9] root ̽�� ----------
>>"%RPT%" echo ===== [5/9] root ̽�� =====
echo su -c id:
"%ADB%" shell "su -c id" >"%T3%" 2>&1
type "%T3%"
type "%T3%" >>"%RPT%"
echo magisk -v:
"%ADB%" shell "su -c magisk -v" >"%T5%" 2>&1
type "%T5%"
type "%T5%" >>"%RPT%"
echo.

rem ------------------- [6/9] /data/adb -------------------
echo ---------- [6/9] /data/adb ���� ----------
>>"%RPT%" echo ===== [6/9] /data/adb ���� =====
"%ADB%" shell "su -c ls -la /data/adb" >"%T1%" 2>&1
type "%T1%"
type "%T1%" >>"%RPT%"
echo.

rem ------------------- [7/9] KernelSU ��� -------------------
echo ---------- [7/9] KernelSU ���Ŀ¼/���� ----------
>>"%RPT%" echo ===== [7/9] KernelSU ��� =====
echo /data/adb/ksu:
"%ADB%" shell "su -c ls -la /data/adb/ksu" >"%T1%" 2>&1
type "%T1%"
type "%T1%" >>"%RPT%"
echo /data/adb/ksud:
"%ADB%" shell "su -c ls -la /data/adb/ksud" >"%T1%" 2>&1
type "%T1%"
type "%T1%" >>"%RPT%"
echo init.svc.ksud:
"%ADB%" shell "getprop init.svc.ksud" >"%T1%" 2>&1
type "%T1%"
type "%T1%" >>"%RPT%"
echo ������� ksud:
"%ADB%" shell "su -c ps -A" >"%T1%" 2>&1
findstr /i /c:"ksud" "%T1%"
findstr /i /c:"ksud" "%T1%" >>"%RPT%"
echo.

rem ------------------- [8/9] �Ѽ����ں�ģ�� -------------------
echo ---------- [8/9] �ں��Ѽ���ģ�� ----------
>>"%RPT%" echo ===== [8/9] �ں��Ѽ���ģ�� =====
set "HASKO="
"%ADB%" shell "su -c cat /proc/modules" >"%T1%" 2>&1
findstr /i /c:"sevenk" /c:"kernelsu" "%T1%" >"%T2%" 2>nul
findstr /i /c:"sevenk" /c:"kernelsu" "%T1%" >nul 2>&1
if not errorlevel 1 set "HASKO=1"
type "%T2%"
if defined HASKO (
  echo   �� sevenk / kernelsu = �ں�ģ���Ѽ���
) else (
  echo   û���� sevenk / kernelsu ģ��
)
type "%T2%" >>"%RPT%"
echo.

rem ------------------- [9/9] �ں���־ -------------------
echo ---------- [9/9] �ں���־ dmesg ----------
>>"%RPT%" echo ===== [9/9] �ں���־ =====
"%ADB%" shell "su -c dmesg" >"%DMP%" 2>&1
findstr /i /c:"KernelSU" /c:"sevenk" /c:"ksud" "%DMP%" >"%T2%" 2>nul
findstr /i /c:"KernelSU" /c:"sevenk" /c:"ksud" "%DMP%" >nul 2>&1
set "HASDMG="
if not errorlevel 1 set "HASDMG=1"
echo �ؼ���ƥ��:
type "%T2%"
if defined HASDMG (
  echo   �������ں��� KernelSU / sevenk �ĺۼ�
) else (
  echo   �ں���־��û���κ� KernelSU / sevenk �ۼ�
)
type "%T2%" >>"%RPT%"
echo ���� dmesg ���浽: %DMP%
echo.

rem ------------------- �Զ����� -------------------
echo ========== �Զ����� ==========
>>"%RPT%" echo.
>>"%RPT%" echo ===== �Զ����� =====

set "ROOT=û�õ� (su ʧ��)"
findstr /i /c:"uid=0" "%T3%" >nul 2>&1
if not errorlevel 1 set "ROOT=�� (uid=0)"
echo   root    : !ROOT!
>>"%RPT%" echo   root    : !ROOT!

set "RAM=û�� sevenk.ko ���� ǿ�粹��û������"
if defined HASRAM set "RAM=�� sevenk.ko ���� ǿ�粹��������"
echo   ramdisk : !RAM!
>>"%RPT%" echo   ramdisk : !RAM!

set "KMSG=û�� KernelSU �ۼ� ���� �ں���û�� KernelSU"
if defined HASDMG set "KMSG=�� KernelSU �ۼ�"
echo   �ں�    : !KMSG!
>>"%RPT%" echo   �ں�    : !KMSG!

echo.
echo �����ļ�: %RPT%
echo �����ں���־: %DMP%
echo ����������ļ�һ�𷢸��ҡ�
echo.
pause
start "" notepad "%RPT%"
del "%T1%" "%T2%" "%T3%" "%T4%" "%T5%" >nul 2>&1
endlocal
