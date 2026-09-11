@echo off
rem ---------------------------------------------------------------------------
rem  TMS113 - builds the server if needed, then starts it.
rem
rem  This is the only .bat in the tree. It compiles src\ into dist\TMS113.jar
rem  whenever anything under src\, JarLib\ or manifest.mf is newer than the jar,
rem  and then launches server.Start; an unchanged tree starts straight away.
rem
rem      Launcher.bat            build if needed, then start the server
rem      Launcher.bat run        start the server, never build
rem      Launcher.bat build      force a full rebuild and stop (no server)
rem      Launcher.bat cashshop   run tools.wztosql.CashShopDumper instead
rem
rem  JDK 26 is required. The sources are UTF-8 and contain Chinese identifiers
rem  (e.g. the inner classes of PlayerCommand), so -encoding UTF-8 is required.
rem  `ant` cannot build this tree: build.xml imports nbproject\build-impl.xml,
rem  and nbproject\ is not in the repository.
rem
rem  The working directory has to stay the repo root - Settings.ini, Libs\,
rem  Logs\ and the wz path are all resolved relative to it.
rem ---------------------------------------------------------------------------
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "MODE=%~1"
if not defined MODE set "MODE=auto"

if /i "%MODE%"=="auto"     goto check
if /i "%MODE%"=="cashshop" goto check
if /i "%MODE%"=="run"      goto run
if /i "%MODE%"=="build"    goto build
echo Usage: Launcher.bat [run^|build^|cashshop]
endlocal
exit /b 1

rem --- is dist\TMS113.jar still newer than every source and jar it was built from?
:check
set "NEED=1"
if exist dist\TMS113.jar (
    for /f "usebackq delims=" %%s in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$j = Get-Item 'dist\TMS113.jar'; $n = '0'; foreach ($f in Get-ChildItem -Path 'src','JarLib','manifest.mf' -Recurse -File -ErrorAction SilentlyContinue) { if ($f.LastWriteTimeUtc -gt $j.LastWriteTimeUtc) { $n = '1' } }; $n"`) do set "NEED=%%s"
)
if "!NEED!"=="0" (
    echo [build] dist\TMS113.jar is up to date.
    goto run
)
goto build

:build
title Build TMS113

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

echo Build OK: dist\TMS113.jar
if /i "%MODE%"=="build" (
    endlocal
    exit /b 0
)

:run
if not exist dist\TMS113.jar (
    echo ERROR: dist\TMS113.jar is missing - run Launcher.bat with no argument to build it.
    goto fail
)
if exist "!JAVA_HOME!\bin\java.exe" (set "JAVA=!JAVA_HOME!\bin\java.exe") else (set "JAVA=java")

rem dist\lib holds MINA, Nashorn (dropped from the JDK in 15, so it ships here),
rem the asm jars Nashorn needs, MySQL Connector/J and slf4j.
set CLASSPATH=.;dist\TMS113.jar;dist\lib\*
if /i "%MODE%"=="cashshop" (
    title CashShopDumper
    "!JAVA!" -Xmx2G -Xms512M -Dnet.sf.odinms.wzpath=wz tools.wztosql.CashShopDumper
) else (
    title TMS113
    "!JAVA!" -Xmx4G -Xms2G -Dnet.sf.odinms.wzpath=wz server.Start
)
pause
endlocal
exit /b 0

:fail
echo.
echo BUILD FAILED
pause
endlocal
exit /b 1
