@echo off
rem ---------------------------------------------------------------------------
rem  Frija v113 - builds the server if needed, then starts it.
rem
rem  This is the only .bat in the tree. It compiles src\ into dist\Frija-v113.jar
rem  whenever anything under src\, JarLib\, panel\dist\ or manifest.mf is newer
rem  than the jar, and then launches server.Start; an unchanged tree starts
rem  straight away.
rem
rem      Launcher.bat            build if needed, then start the server
rem      Launcher.bat run        start the server, never build
rem      Launcher.bat build      force a full rebuild and stop (no server)
rem      Launcher.bat cashshop   run tools.wztosql.CashShopDumper instead
rem      Launcher.bat lint       compile only with -Xlint:all, print the
rem                              warning count by category, and stop
rem      Launcher.bat test       compile src\ and test\, run the JUnit suite
rem                              and stop; non-zero exit if anything fails
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
rem  JDK 25 (the current LTS) or newer is required; the build targets
rem  --release 25, so a newer JDK produces the same class files. Launcher.bat is
rem  itself one of the inputs the staleness check looks at, so changing a build
rem  flag here rebuilds the jar on the next start.
rem
rem  The sources are UTF-8 and contain Chinese identifiers
rem  (e.g. the inner classes of PlayerCommand), so -encoding UTF-8 is required.
rem  There is no ant or IDE project file any more; this script is the build.
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
if /i "%MODE%"=="lint"     goto lint
if /i "%MODE%"=="test"     goto test
echo Usage: Launcher.bat [run^|build^|cashshop^|lint^|test]
endlocal
exit /b 1

rem --- What is stale? One PowerShell call answers two questions:
rem       NEED_WEB  is anything in panel\ newer than panel\dist\index.html?
rem       NEED      is anything the jar is built from (src\, JarLib\, manifest.mf,
rem                 panel\dist\ and this file's own build flags) newer than the jar, or is
rem                 a JarLib jar missing from dist\lib? (dist\ is not tracked, so
rem                 a pull can leave an up-to-date jar with no dist\lib beside it.)
rem     A frontend rebuild always implies a jar rebuild, since the jar holds it.
:check
set "NEED_WEB=0"
set "NEED=1"
for /f "usebackq tokens=1,2" %%w in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$web = '0'; $jar = '1'; if (Test-Path 'panel\package.json') { $web = '1'; if (Test-Path 'panel\dist\index.html') { $web = '0'; $d = (Get-Item 'panel\dist\index.html').LastWriteTimeUtc; $files = @(Get-ChildItem -Path 'panel' -File -ErrorAction SilentlyContinue) + @(Get-ChildItem -Path 'panel\src','panel\public' -Recurse -File -ErrorAction SilentlyContinue); foreach ($f in $files) { if ($f.LastWriteTimeUtc -gt $d) { $web = '1'; break } } } }; if (Test-Path 'dist\Frija-v113.jar') { $jar = '0'; $j = (Get-Item 'dist\Frija-v113.jar').LastWriteTimeUtc; foreach ($f in Get-ChildItem -Path 'src','JarLib','manifest.mf','Launcher.bat','panel\dist' -Recurse -File -ErrorAction SilentlyContinue) { if ($f.LastWriteTimeUtc -gt $j) { $jar = '1'; break } }; foreach ($l in Get-ChildItem -Path 'JarLib\*.jar' -File -ErrorAction SilentlyContinue) { if (-not (Test-Path ('dist\lib\' + $l.Name))) { $jar = '1'; break } } }; Write-Output ($web + ' ' + $jar)"`) do (
    set "NEED_WEB=%%w"
    set "NEED=%%x"
)
if "!NEED_WEB!"=="1" set "NEED=1"
if /i "%MODE%"=="build" set "NEED=1"
if "!NEED!"=="0" (
    echo [build] dist\Frija-v113.jar is up to date.
    goto run
)

:build
title Build Frija v113

echo [1/5] Locating the JDK...
rem PATH may hold only the Oracle javapath shim, which has java.exe and
rem javac.exe but no jar.exe - so ask the JVM where it actually lives.
if not exist "%JAVA_HOME%\bin\javac.exe" (
    for /f "tokens=1,* delims==" %%a in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.home"') do set "JAVA_HOME=%%b"
    for /f "tokens=* delims= " %%a in ("!JAVA_HOME!") do set "JAVA_HOME=%%a"
)
if not exist "%JAVA_HOME%\bin\javac.exe" (
    echo ERROR: no JDK found. Set JAVA_HOME to a JDK 25 ^(or newer^) installation.
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
"%JAVA_HOME%\bin\javac.exe" --release 25 -encoding UTF-8 -Xlint:all -Xmaxwarns 10000 -cp "JarLib/*" -d build\classes "@build\sources.txt" 2> build\javac.log
if errorlevel 1 (
    type build\javac.log
    goto fail
)
for /f "usebackq" %%n in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "(Select-String -Path 'build\javac.log' -Pattern 'warning:').Count"`) do set "WARN_COUNT=%%n"
echo [build] !WARN_COUNT! warnings ^(see build\javac.log^)
rem The frontend rides along as classpath resources next to server.panel.
if exist panel\dist\index.html (
    xcopy /s /e /y /q /i panel\dist build\classes\server\panel\web >nul
    if errorlevel 1 goto fail
)

echo [5/5] Packaging dist\Frija-v113.jar...
if not exist dist mkdir dist
"%JAVA_HOME%\bin\jar.exe" cfm dist\Frija-v113.jar manifest.mf -C build\classes .
if errorlevel 1 goto fail

echo Build OK: dist\Frija-v113.jar
if /i "%MODE%"=="build" (
    endlocal
    exit /b 0
)

:run
if not exist dist\Frija-v113.jar (
    echo ERROR: dist\Frija-v113.jar is missing - run Launcher.bat with no argument to build it.
    goto fail
)
if exist "!JAVA_HOME!\bin\java.exe" (set "JAVA=!JAVA_HOME!\bin\java.exe") else (set "JAVA=java")

rem dist\lib holds MINA, Nashorn (dropped from the JDK in 15, so it ships here),
rem the asm jars Nashorn needs, MySQL Connector/J and slf4j.
set CLASSPATH=.;dist\Frija-v113.jar;dist\lib\*

rem Everything logs through java.lang.System.Logger, which is backed by
rem java.util.logging - and so is MINA, via slf4j-jdk14. Libs\logging.properties
rem sets the console layout, its charset (native.encoding, i.e. MS950 here, not
rem the UTF-8 that Charset.defaultCharset() has returned since JDK 18) and the
rem levels. The path is relative to the repo root, which is the working
rem directory both modes below are started in.
rem
rem The "if exist" is not belt and braces. Pointing this property at a file
rem that is not there does NOT fall back to the JDK's own conf\logging.properties
rem and does NOT print a word about it - LogManager ends up with no handlers at
rem all and the console goes completely silent, which on a server whose startup
rem banner is the only sign of life reads exactly like a hang. So the property
rem is only passed when the file is really there.
rem
rem The dumper gets it too. Its own System.out output is its user interface and
rem is untouched by any of this, but it drags in DatabaseConnection and the wz
rem providers, and anything they log should land on the same console, in the
rem same encoding, in the same format as the server's - not in the JDK
rem default handler's two-line stderr format.
set "LOGCFG="
if exist Libs\logging.properties (
    set "LOGCFG=-Djava.util.logging.config.file=Libs/logging.properties"
) else (
    echo WARNING: Libs\logging.properties is missing. The console will fall back to
    echo          the JDK's own two-line format and the Chinese in it may not be
    echo          readable; the Logs\*.rtf files are unaffected.
)

if /i "%MODE%"=="cashshop" (
    title CashShopDumper
    "!JAVA!" -Xmx2G -Xms512M !LOGCFG! -Dnet.sf.odinms.wzpath=wz tools.wztosql.CashShopDumper
) else (
    title Frija v113
    "!JAVA!" -Xmx4G -Xms2G !LOGCFG! -Dnet.sf.odinms.wzpath=wz server.Start
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

rem --- Compile-only mode: -Xlint:all, then tally the warnings by category
rem     (the batch equivalent of Appendix A's
rem     "grep -oE ... | sort | uniq -c | sort -rn", done in one PowerShell
rem     call since batch has neither grep nor sort). No panel, no jar, no
rem     server - the panel frontend and dist\lib have nothing to do with
rem     whether src\ compiles cleanly.
:lint
title Lint Frija v113

echo [1/2] Locating the JDK...
rem PATH may hold only the Oracle javapath shim, which has java.exe and
rem javac.exe but no jar.exe - so ask the JVM where it actually lives.
if not exist "%JAVA_HOME%\bin\javac.exe" (
    for /f "tokens=1,* delims==" %%a in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.home"') do set "JAVA_HOME=%%b"
    for /f "tokens=* delims= " %%a in ("!JAVA_HOME!") do set "JAVA_HOME=%%a"
)
if not exist "%JAVA_HOME%\bin\javac.exe" (
    echo ERROR: no JDK found. Set JAVA_HOME to a JDK 25 ^(or newer^) installation.
    goto fail
)
echo       JAVA_HOME=%JAVA_HOME%

echo [2/2] Compiling with -Xlint:all...
if exist build\classes rmdir /s /q build\classes
mkdir build\classes
dir /s /b src\*.java > build\sources.txt
"%JAVA_HOME%\bin\javac.exe" --release 25 -encoding UTF-8 -Xlint:all -Xmaxwarns 10000 -cp "JarLib/*" -d build\classes "@build\sources.txt" 2> build\javac.log
if errorlevel 1 (
    type build\javac.log
    goto fail
)

for /f "usebackq" %%n in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "(Select-String -Path 'build\javac.log' -Pattern 'warning:').Count"`) do set "WARN_COUNT=%%n"
echo [lint] !WARN_COUNT! warnings
powershell -NoProfile -ExecutionPolicy Bypass -Command "Select-String -Path 'build\javac.log' -Pattern 'warning:\s*\[([a-zA-Z0-9-]+)\]' | ForEach-Object { $_.Matches[0].Groups[1].Value } | Group-Object | Sort-Object Count -Descending | ForEach-Object { '{0,10}  [{1}]' -f $_.Count, $_.Name }"

endlocal
exit /b 0

rem --- Test mode: compile src\, compile test\ against it, and hand the result
rem     to JUnit's console launcher. No panel, no dist\lib, no jar - the tests
rem     run straight off build\classes, so this mode needs none of the things
rem     the server needs at run time (Settings.ini, a database, wz\).
rem
rem     JUnit ships as a single standalone jar in JarLib\test\ and is
rem     deliberately NOT in manifest.mf, dist\lib\ or the server's classpath;
rem     nothing under src\ may ever import it.
rem
rem     The launcher is started as a class on -cp rather than with -jar,
rem     because its own --class-path option takes literal paths and will not
rem     expand the "JarLib\*" wildcard that java itself understands.
:test
title Test Frija v113

echo [1/4] Locating the JDK...
rem PATH may hold only the Oracle javapath shim, which has java.exe and
rem javac.exe but no jar.exe - so ask the JVM where it actually lives.
if not exist "%JAVA_HOME%\bin\javac.exe" (
    for /f "tokens=1,* delims==" %%a in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.home"') do set "JAVA_HOME=%%b"
    for /f "tokens=* delims= " %%a in ("!JAVA_HOME!") do set "JAVA_HOME=%%a"
)
if not exist "%JAVA_HOME%\bin\javac.exe" (
    echo ERROR: no JDK found. Set JAVA_HOME to a JDK 25 ^(or newer^) installation.
    goto testfail
)
echo       JAVA_HOME=%JAVA_HOME%
set "JUNIT="
for %%f in (JarLib\test\junit-platform-console-standalone-*.jar) do set "JUNIT=%%f"
if not defined JUNIT (
    echo ERROR: no JarLib\test\junit-platform-console-standalone-*.jar - the test runner is missing.
    goto testfail
)
echo       JUNIT=!JUNIT!

echo [2/4] Compiling src\...
if exist build\classes rmdir /s /q build\classes
mkdir build\classes
dir /s /b src\*.java > build\sources.txt
"%JAVA_HOME%\bin\javac.exe" --release 25 -encoding UTF-8 -nowarn -cp "JarLib/*" -d build\classes "@build\sources.txt" 2> build\javac.log
if errorlevel 1 (
    type build\javac.log
    goto testfail
)

echo [3/4] Compiling test\...
if exist build\test-classes rmdir /s /q build\test-classes
mkdir build\test-classes
dir /s /b test\*.java > build\test-sources.txt
"%JAVA_HOME%\bin\javac.exe" --release 25 -encoding UTF-8 -nowarn -cp "build\classes;JarLib/*;JarLib/test/*" -d build\test-classes "@build\test-sources.txt" 2> build\javac-test.log
if errorlevel 1 (
    type build\javac-test.log
    goto testfail
)

rem The working directory is the repo root (set at the top of this file), which
rem RecvPacketOpcodeTest needs: it loads Libs\opcode\recv.ini by relative path.
echo [4/4] Running the tests...
"%JAVA_HOME%\bin\java.exe" -cp "!JUNIT!;build\classes;build\test-classes;JarLib\*" org.junit.platform.console.ConsoleLauncher execute --scan-classpath=build\test-classes --details=summary --disable-banner --fail-if-no-tests
if errorlevel 1 goto testfail

echo Tests OK
endlocal
exit /b 0

:testfail
echo.
echo TESTS FAILED
pause
endlocal
exit /b 1

:fail
echo.
echo BUILD FAILED
pause
endlocal
exit /b 1
