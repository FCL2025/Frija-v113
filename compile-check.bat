@echo off
rem ---------------------------------------------------------------------------
rem  Compile-only check: javac over src\ into build\check, no jar, no dist sync.
rem  Use this for a fast "does it still build?" loop; build.bat produces the jar.
rem ---------------------------------------------------------------------------
setlocal enabledelayedexpansion
cd /d "%~dp0"

if not exist "%JAVA_HOME%\bin\javac.exe" (
    for /f "tokens=1,* delims==" %%a in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.home"') do set "JAVA_HOME=%%b"
    for /f "tokens=* delims= " %%a in ("!JAVA_HOME!") do set "JAVA_HOME=%%a"
)
if not exist "%JAVA_HOME%\bin\javac.exe" (
    echo ERROR: no JDK found. Set JAVA_HOME to a JDK 26 installation.
    exit /b 1
)

if exist build\check rmdir /s /q build\check
mkdir build\check
dir /s /b src\*.java > build\sources.txt
"%JAVA_HOME%\bin\javac.exe" --release 26 -encoding UTF-8 -nowarn -cp "JarLib/*" -d build\check "@build\sources.txt"
if errorlevel 1 (
    echo COMPILE FAILED
    exit /b 1
)
echo COMPILE OK
exit /b 0
