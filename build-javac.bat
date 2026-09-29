@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem =====================================================================
rem  YinwuLastServer builder
rem
rem  IMPORTANT - you MUST use JDK 25 (or newer).
rem  velocity-4.2.0-30.jar contains classes compiled for class file 69
rem  (Java 25). javac 17 / 21 cannot even READ that jar:
rem     "cannot access com.velocitypowered.api.event.Subscribe"
rem     "class file has wrong version 69.0, should be 61.0 / 65.0"
rem  We still emit Java 17 bytecode by passing --release 17.
rem
rem  No Maven, no internet needed. All text is ASCII on purpose.
rem =====================================================================

set "OUT_JAR=velocity-lastserver-1.1.0.jar"

rem ---------- 1. proxy jar ----------
set "PROXY_JAR="
if exist "..\velocity\velocity-4.2.0-30.jar" set "PROXY_JAR=..\velocity\velocity-4.2.0-30.jar"
if not defined PROXY_JAR (
  for %%F in ("..\velocity\velocity-*.jar") do (
    if not defined PROXY_JAR set "PROXY_JAR=%%~fF"
  )
)
if not defined PROXY_JAR (
  echo [ERR] No velocity-*.jar found under ..\velocity\
  echo       Open this script and set PROXY_JAR manually.
  pause
  exit /b 1
)

rem ---------- 2. javac (JDK 25+ required) ----------
set "JAVAC="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC=%JAVA_HOME%\bin\javac.exe"
if not defined JAVAC (
  for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-25*") do (
    if not defined JAVAC if exist "%%~fD\bin\javac.exe" set "JAVAC=%%~fD\bin\javac.exe"
  )
)
if not defined JAVAC if exist "C:\Program Files\Java\jdk-25\bin\javac.exe" set "JAVAC=C:\Program Files\Java\jdk-25\bin\javac.exe"
if not defined JAVAC (
  where javac.exe >nul 2>nul && set "JAVAC=javac.exe"
)
if not defined JAVAC (
  echo [ERR] javac.exe not found. Install JDK 25, or set JAVA_HOME.
  pause
  exit /b 1
)

set "JAR=jar.exe"
for %%D in ("%JAVAC%") do if not "%%~dpD"=="" set "JAR=%%~dpDjar.exe"

echo Proxy jar : %PROXY_JAR%
echo javac     : %JAVAC%
"%JAVAC%" -version
echo.

rem ---------- 3. compile ----------
if exist out rmdir /s /q out
mkdir out
echo [1/2] compiling ...
"%JAVAC%" -encoding UTF-8 --release 17 -proc:none -cp "%PROXY_JAR%" -d out src\main\java\io\yinwu\lastserver\LastServerPlugin.java
if errorlevel 1 goto fail

rem ---------- 4. package ----------
echo [2/2] packaging ...
"%JAR%" --create --file "%OUT_JAR%" -C out . -C src\main\resources .
if errorlevel 1 goto fail

echo.
echo [OK] %CD%\%OUT_JAR%
echo      Copy it to ..\velocity\plugins\ , make sure authbridge (MirrorBridge)
echo      is running, then restart the proxy.
pause
exit /b 0

:fail
echo.
echo [ERR] build failed.
echo       If you see "class file has wrong version 69.0" or
echo       "cannot access ... Subscribe", your javac is too old:
echo       use JDK 25, e.g. set JAVA_HOME to the Adoptium jdk-25 folder.
pause
exit /b 1
