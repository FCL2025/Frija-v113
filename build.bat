@echo off
rem ---------------------------------------------------------------------------
rem  Builds dist\TMS113.jar from src\ with the JDK on PATH.
rem
rem  JDK 26 is required. The sources are UTF-8 and contain Chinese identifiers
rem  (e.g. the inner classes of PlayerCommand), so -encoding UTF-8 is required.
rem
rem  build.xml is the NetBeans stub that imports nbproject\build-impl.xml, and
rem  nbproject\ is not in the repository - so `ant` cannot build this tree from
rem  a clean checkout. This script is the way that works.
rem ---------------------------------------------------------------------------
setlocal enabledelayedexpansion
title Build TMS113

cd /d "%~dp0"

echo [1/4] Locating the JDK...
rem PATH may hold only the Oracle javapath shim, which has java.exe and
rem javac.exe but no jar.exe - so ask the JVM where it actually lives.
if not exist "%JAVA_HOME%\bin\javac.exe" (
    for /f "tokens=1,* delims==" %%a in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.home"') do set "JAVA_HOME=%%b"
    for /f "tokens=* delims= " %%a in ("!JAVA_HOME!") do set "JAVA_HOME=%%a"
)
if not exist "%JAVA_HOME%\bin\javac.exe" (
    echo ERROR: no JDK found. Set JAVA_HOME to a JDK 26 installation.
    goto fail
)
if not exist "%JAVA_HOME%\bin\jar.exe" (
    echo ERROR: "%JAVA_HOME%" has no jar.exe - that is a JRE, not a JDK.
    goto fail
)
echo       JAVA_HOME=%JAVA_HOME%
"%JAVA_HOME%\bin\javac.exe" -version

echo [2/4] Syncing dist\lib from JarLib...
if not exist dist\lib mkdir dist\lib
xcopy /y /q JarLib\*.jar dist\lib\ >nul
if errorlevel 1 goto fail

echo [3/4] Compiling...
if exist build\classes rmdir /s /q build\classes
mkdir build\classes
dir /s /b src\*.java > build\sources.txt
"%JAVA_HOME%\bin\javac.exe" --release 26 -encoding UTF-8 -nowarn -cp "JarLib/*" -d build\classes "@build\sources.txt"
if errorlevel 1 goto fail

echo [4/4] Packaging dist\TMS113.jar...
if not exist dist mkdir dist
"%JAVA_HOME%\bin\jar.exe" cfm dist\TMS113.jar manifest.mf -C build\classes .
if errorlevel 1 goto fail

echo.
echo Build OK: dist\TMS113.jar
echo Run the server with Launcher.bat
endlocal
exit /b 0

:fail
echo.
echo BUILD FAILED
endlocal
exit /b 1
