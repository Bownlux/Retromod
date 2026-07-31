@echo off
REM Retromod multi-version build script for Windows
REM Copyright (c) 2026 RevivalSMP. MIT License.
REM
REM Builds Retromod for every supported host:
REM   - Fabric (1.20 through 26.1)
REM   - Forge (1.20 through 26.1)
REM   - NeoForge (1.20.1 through 26.1)
REM   - Standalone CLI
REM Older mods are translated at runtime and do not need separate host jars.

setlocal enabledelayedexpansion

set VERSION=1.2.0-snapshot.8
REM Only supported host versions need their own jars.
set MC_VERSIONS=1.20 1.20.1 1.20.2 1.20.3 1.20.4 1.20.5 1.20.6 1.21 1.21.1 1.21.2 1.21.3 1.21.4 1.21.5 1.21.6 1.21.7 1.21.8 1.21.9 1.21.10 1.21.11 26.1 26.1.1 26.1.2
set LOADERS=fabric forge neoforge

echo Retromod multi-version build %VERSION%
echo.

REM Check for Maven
where mvn >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo ERROR: Maven not found!
    echo Install: https://maven.apache.org/install.html
    exit /b 1
)

REM Check for Java 25+
where java >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo ERROR: Java not found! Java 25+ is required.
    echo Install from: https://adoptium.net/
    exit /b 1
)
for /f "tokens=3" %%i in ('java -version 2^>^&1 ^| findstr /i "version"') do set JAVA_VER_STR=%%~i
for /f "tokens=1 delims=." %%j in ("%JAVA_VER_STR%") do set JAVA_VER=%%j
if %JAVA_VER% LSS 25 (
    echo ERROR: Java 25 or later is required! You have Java %JAVA_VER%.
    echo Install from: https://adoptium.net/
    exit /b 1
)

REM Check for --skip-build flag (used when called from Maven)
set SKIP_BUILD=0
if "%1"=="--skip-build" set SKIP_BUILD=1

if %SKIP_BUILD%==0 (
    echo [Step 1] Building base JAR with Maven...
    call mvn clean package -DskipTests -q
    if %ERRORLEVEL% neq 0 (
        echo ERROR: Maven build failed!
        exit /b 1
    )
)

REM Find the shaded JAR (with all dependencies bundled)
set SHADED_JAR=target\retromod-%VERSION%-all.jar
if not exist "%SHADED_JAR%" (
    echo ERROR: Shaded JAR not found at %SHADED_JAR%
    echo Make sure maven-shade-plugin ran successfully.
    exit /b 1
)

echo   Shaded JAR: %SHADED_JAR% (with bundled dependencies)

REM Remove stale JARs from previous builds (e.g. retromod-1.0.0-beta.1+*.jar
REM left behind after a version bump). Only deletes the retromod-* naming
REM pattern - anything else in dist\ is preserved. See build-all.sh for the
REM rationale.
if exist dist (
    for /r dist %%f in (retromod-*.jar) do del /q "%%f" >nul 2>&1
)

REM Create output directories
mkdir dist\Fabric 2>nul
mkdir dist\Forge 2>nul
mkdir dist\NeoForge 2>nul
mkdir dist\CLI 2>nul

echo.
echo [Step 2] Creating CLI tool...

copy /Y "%SHADED_JAR%" "dist\CLI\retromod-%VERSION%-cli.jar" >nul
echo   Created: dist\CLI\retromod-%VERSION%-cli.jar

echo.
echo [Step 3] Creating loader-specific JARs...

set TOTAL=0
set FAILED=0

for %%L in (%LOADERS%) do (
    echo.
    if "%%L"=="fabric" echo Building Fabric JARs...
    if "%%L"=="forge" echo Building Forge JARs...
    if "%%L"=="neoforge" echo Building NeoForge JARs...

    for %%V in (%MC_VERSIONS%) do (
        call :create_mod_jar %%L %%V
    )
)

echo.
echo [Step 4] Done!
echo.
echo Build complete
echo.

REM Count JARs
set /a JAR_COUNT=0
for /r dist %%f in (*.jar) do set /a JAR_COUNT+=1
echo   Total JARs: %JAR_COUNT%

if %FAILED% gtr 0 (
    echo   %FAILED% JAR(s) failed to build.
)

echo.
echo Output in dist\ folder
echo.
goto :eof

:create_mod_jar
set LOADER=%1
set MC_VERSION=%2

REM Set loader directory name
set LOADER_DIR=
if "%LOADER%"=="fabric" set LOADER_DIR=Fabric
if "%LOADER%"=="forge" set LOADER_DIR=Forge
if "%LOADER%"=="neoforge" set LOADER_DIR=NeoForge

REM Guard: never emit a dist\ artifact for a host MC below 1.20.
REM Retromod requires Java 25 and MC 1.20+ to run as a mod - earlier versions
REM are only relevant as translation SOURCES, not as host targets. This filter
REM prevents accidental additions to MC_VERSIONS from producing broken builds.
REM Allow-list: 1.20.x, 1.21.x, 26.x, 27.x, 28.x (forward-compat).
set HOST_OK=0
echo %MC_VERSION% | findstr /b "1.20" >nul && set HOST_OK=1
echo %MC_VERSION% | findstr /b "1.21" >nul && set HOST_OK=1
echo %MC_VERSION% | findstr /b "26." >nul && set HOST_OK=1
echo %MC_VERSION% | findstr /b "27." >nul && set HOST_OK=1
echo %MC_VERSION% | findstr /b "28." >nul && set HOST_OK=1
if "%MC_VERSION%"=="26.1" set HOST_OK=1
if "%HOST_OK%"=="0" (
    echo   skip: %LOADER% %MC_VERSION% - host MC ^< 1.20 not built into dist\
    goto :eof
)

REM Skip Fabric for versions before 1.14 (Fabric didn't exist yet)
if "%LOADER%"=="fabric" (
    echo %MC_VERSION% | findstr /b "1.12" >nul && goto :eof
    echo %MC_VERSION% | findstr /b "1.13" >nul && goto :eof
)

REM Skip NeoForge for versions before 1.20.1 (NeoForge didn't exist yet)
REM AND for MC patch releases NeoForge didn't ship a build for:
REM   - 26.1 and 26.1.1 were skipped by NeoForge; only 26.1.2 has releases
REM Keep in sync with https://maven.neoforged.net/releases/net/neoforged/neoforge/
if "%LOADER%"=="neoforge" (
    echo %MC_VERSION% | findstr /b "1.12" >nul && goto :eof
    echo %MC_VERSION% | findstr /b "1.13" >nul && goto :eof
    echo %MC_VERSION% | findstr /b "1.14" >nul && goto :eof
    echo %MC_VERSION% | findstr /b "1.15" >nul && goto :eof
    echo %MC_VERSION% | findstr /b "1.16" >nul && goto :eof
    echo %MC_VERSION% | findstr /b "1.17" >nul && goto :eof
    echo %MC_VERSION% | findstr /b "1.18" >nul && goto :eof
    echo %MC_VERSION% | findstr /b "1.19" >nul && goto :eof
    if "%MC_VERSION%"=="1.20" goto :eof
    if "%MC_VERSION%"=="26.1" goto :eof
    if "%MC_VERSION%"=="26.1.1" goto :eof
)

set OUTPUT_NAME=retromod-%VERSION%+%MC_VERSION%.jar
mkdir "dist\%LOADER_DIR%\%MC_VERSION%" 2>nul

REM Create temp directory
set TEMP_DIR=%TEMP%\retromod-build-%LOADER%-%MC_VERSION%
if exist "%TEMP_DIR%" rmdir /s /q "%TEMP_DIR%"
mkdir "%TEMP_DIR%"

REM Extract shaded JAR (includes all dependencies like ASM, Gson, TOML4J)
cd /d "%TEMP_DIR%"
jar xf "%~dp0%SHADED_JAR%" 2>nul
if %ERRORLEVEL% neq 0 (
    echo   FAILED: %MC_VERSION%
    set /a FAILED+=1
    cd /d "%~dp0"
    rmdir /s /q "%TEMP_DIR%" 2>nul
    goto :eof
)

REM Remove other loaders' files based on target loader
if "%LOADER%"=="fabric" (
    del /q "META-INF\neoforge.mods.toml" 2>nul
    del /q "META-INF\mods.toml" 2>nul
    del /q "pack.mcmeta" 2>nul
)
if "%LOADER%"=="forge" (
    del /q "fabric.mod.json" 2>nul
    del /q "META-INF\neoforge.mods.toml" 2>nul
)
if "%LOADER%"=="neoforge" (
    del /q "fabric.mod.json" 2>nul
    del /q "META-INF\mods.toml" 2>nul
)

REM Update manifest
mkdir META-INF 2>nul
(
echo Manifest-Version: 1.0
echo Implementation-Title: Retromod
echo Implementation-Version: %VERSION%
echo Retromod-Target-MC: %MC_VERSION%
echo Retromod-Loader: %LOADER%
echo Automatic-Module-Name: retromod
) > META-INF\MANIFEST.MF

REM Repackage
jar cfm "%~dp0dist\%LOADER_DIR%\%MC_VERSION%\%OUTPUT_NAME%" META-INF\MANIFEST.MF . 2>nul
if %ERRORLEVEL% neq 0 (
    echo   FAILED: %MC_VERSION%
    set /a FAILED+=1
) else (
    echo   Created: %MC_VERSION%
    set /a TOTAL+=1
)

cd /d "%~dp0"
rmdir /s /q "%TEMP_DIR%" 2>nul
goto :eof
