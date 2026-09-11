@echo off
rem ---------------------------------------------------------------------------
rem  TMS113 - builds the server if needed, then starts it.
rem
rem  This is the only .bat in the tree. It compiles src\ into dist\TMS113.jar
rem  whenever anything under src\, JarLib\, panel\dist\ or manifest.mf is newer
rem  than the jar, and then launches server.Start; an unchanged tree starts
rem  straight away.
rem
rem      Launcher.bat            build if needed, then start the server
rem      Launcher.bat run        start the server, never build
rem      Launcher.bat build      force a full rebuild and stop (no server)
rem      Launcher.bat cashshop   run tools.wztosql.CashShopDumper instead
rem
rem  The management panel's frontend lives in panel\ (a Vite project) and is
rem  served out of the jar. It is two separate steps, on purpose:
rem    1. "npm run build" in panel\ - slow, so it only runs when a file under
rem       panel\ (excluding node_modules\ and dist\) is newer than
rem       panel\dist\index.html;
rem    2. copying panel\dist into build\classes\server\panel\web\ - fast, and
rem       done on *every* jar build, because build\classes is wiped each time.
rem  A tree without Node, or without panel\node_modules, still builds and
rem  runs; it just says so, and the panel serves a "not built" page.
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
if /i "%MODE%"=="build"    goto check
if /i "%MODE%"=="run"      goto run
echo Usage: Launcher.bat [run^|build^|cashshop]
endlocal
exit /b 1

rem --- What is stale? One PowerShell call answers two questions:
rem       NEED_WEB  is anything in panel\ newer than panel\dist\index.html?
rem       NEED      is anything the jar is built from newer than the jar?
rem     A frontend rebuild always implies a jar rebuild, since the jar holds it.
:check
set "NEED_WEB=0"
set "NEED=1"
for /f "usebackq tokens=1,2" %%w in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$web = '0'; $jar = '1'; if (Test-Path 'panel\package.json') { $web = '1'; if (Test-Path 'panel\dist\index.html') { $web = '0'; $d = (Get-Item 'panel\dist\index.html').LastWriteTimeUtc; $files = @(Get-ChildItem -Path 'panel' -File -ErrorAction SilentlyContinue) + @(Get-ChildItem -Path 'panel\src','panel\public' -Recurse -File -ErrorAction SilentlyContinue); foreach ($f in $files) { if ($f.LastWriteTimeUtc -gt $d) { $web = '1'; break } } } }; if (Test-Path 'dist\TMS113.jar') { $jar = '0'; $j = (Get-Item 'dist\TMS113.jar').LastWriteTimeUtc; foreach ($f in Get-ChildItem -Path 'src','JarLib','manifest.mf','panel\dist' -Recurse -File -ErrorAction SilentlyContinue) { if ($f.LastWriteTimeUtc -gt $j) { $jar = '1'; break } } }; Write-Output ($web + ' ' + $jar)"`) do (
    set "NEED_WEB=%%w"
    set "NEED=%%x"
)
if "!NEED_WEB!"=="1" set "NEED=1"
if /i "%MODE%"=="build" set "NEED=1"
if "!NEED!"=="0" (
    echo [build] dist\TMS113.jar is up to date.
    goto run
)

:build
title Build TMS113

echo [1/5] Locating the JDK...
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

echo [2/5] Panel frontend...
if "!NEED_WEB!"=="1" (
    call :web
    if errorlevel 1 goto fail
) else if exist panel\dist\index.html (
    echo       panel\dist is up to date.
) else (
    echo       no panel\ project - the jar will have no panel frontend.
)

echo [3/5] Syncing dist\lib from JarLib...
if not exist dist\lib mkdir dist\lib
xcopy /y /q JarLib\*.jar dist\lib\ >nul
if errorlevel 1 goto fail

echo [4/5] Compiling...
if exist build\classes rmdir /s /q build\classes
mkdir build\classes
dir /s /b src\*.java > build\sources.txt
"%JAVA_HOME%\bin\javac.exe" --release 26 -encoding UTF-8 -nowarn -cp "JarLib/*" -d build\classes "@build\sources.txt"
if errorlevel 1 goto fail
rem The frontend rides along as classpath resources next to server.panel.
if exist panel\dist\index.html (
    xcopy /s /e /y /q /i panel\dist build\classes\server\panel\web >nul
    if errorlevel 1 goto fail
)

echo [5/5] Packaging dist\TMS113.jar...
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

rem --- Build panel\dist with Vite. Returns 1 only when npm itself fails;
rem     a missing Node or a missing node_modules is reported and skipped, so
rem     the game server still builds on a machine that has no Node at all.
rem     No "npm install" ever runs from here: that is a network operation the
rem     person at the keyboard should choose to run.
:web
where node >nul 2>nul
if errorlevel 1 (
    echo.
    echo       WARNING: panel\ has changed but Node.js is not on PATH.
    echo                Skipping the frontend build - the jar will carry whatever
    echo                panel\dist holds now, or no panel at all.
    echo                Install Node 20+ from https://nodejs.org/ to build it.
    echo.
    exit /b 0
)
if not exist panel\node_modules (
    echo.
    echo       WARNING: panel\node_modules is missing - run this once, then build again:
    echo                    cd panel ^&^& npm install
    echo                Skipping the frontend build.
    echo.
    exit /b 0
)
echo       npm run build ^(panel\^)...
pushd panel
call npm run build
set "WEB_RC=!errorlevel!"
popd
if not "!WEB_RC!"=="0" (
    echo ERROR: the frontend build failed ^(npm exit code !WEB_RC!^).
    exit /b 1
)
exit /b 0

:fail
echo.
echo BUILD FAILED
pause
endlocal
exit /b 1
