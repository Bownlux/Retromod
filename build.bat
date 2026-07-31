@echo off
REM Retromod build script for Windows
REM Copyright (c) 2026 RevivalSMP. MIT License.
REM Builds the CLI and loader-specific jars.

set VERSION=1.2.0-snapshot.8

echo Retromod build %VERSION%
echo.

REM Check for Maven
where mvn >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo Maven was not found. Install it from https://maven.apache.org/install.html
    exit /b 1
)

echo Building Retromod...
echo.

echo [1/5] Cleaning previous build...
call mvn clean -q
if %ERRORLEVEL% neq 0 (
    echo ERROR: Clean failed!
    exit /b 1
)

echo [2/5] Compiling source code...
call mvn compile -q
if %ERRORLEVEL% neq 0 (
    echo ERROR: Compile failed!
    exit /b 1
)

echo [3/5] Running tests...
call mvn test -q
if %ERRORLEVEL% neq 0 (
    echo Warning: Some tests failed, continuing...
)

echo [4/5] Packaging JARs...
call mvn package -q -DskipTests
if %ERRORLEVEL% neq 0 (
    echo ERROR: Packaging failed!
    exit /b 1
)

echo [5/5] Creating distribution packages...

REM Create dist folder
if not exist dist mkdir dist

REM The main shaded JAR becomes the CLI tool
if exist "target\retromod-%VERSION%-all.jar" (
    copy "target\retromod-%VERSION%-all.jar" "dist\retromod-%VERSION%-cli.jar" >nul
    echo   + CLI tool: dist\retromod-%VERSION%-cli.jar
)

REM Create Fabric mod JAR
echo   Creating Fabric mod...
if exist "target\fabric-build" rmdir /s /q "target\fabric-build"
mkdir "target\fabric-build"
cd "target\fabric-build"

jar xf "..\retromod-%VERSION%.jar"

REM Remove NeoForge-specific files
if exist "META-INF\neoforge.mods.toml" del "META-INF\neoforge.mods.toml"
if exist "com\retromod\core\RetromodNeoForge.class" del "com\retromod\core\RetromodNeoForge.class"

REM Repackage as Fabric mod
jar cfm "..\..\dist\retromod-%VERSION%-fabric.jar" META-INF\MANIFEST.MF .
cd ..\..
echo   + Fabric mod: dist\retromod-%VERSION%-fabric.jar

REM Create NeoForge mod JAR
echo   Creating NeoForge mod...
if exist "target\neoforge-build" rmdir /s /q "target\neoforge-build"
mkdir "target\neoforge-build"
cd "target\neoforge-build"

jar xf "..\retromod-%VERSION%.jar"

REM Remove Fabric-specific files
if exist "fabric.mod.json" del "fabric.mod.json"

REM Update manifest for NeoForge
(
echo Manifest-Version: 1.0
echo Implementation-Title: Retromod
echo Implementation-Version: 1.2.0-snapshot.8
echo Automatic-Module-Name: retromod
) > META-INF\MANIFEST.MF

REM Repackage as NeoForge mod
jar cfm "..\..\dist\retromod-%VERSION%-neoforge.jar" META-INF\MANIFEST.MF .
cd ..\..
echo   + NeoForge mod: dist\retromod-%VERSION%-neoforge.jar

REM Copy icon to dist
if exist "assets\icon_512.png" copy "assets\icon_512.png" dist\ >nul

REM Create CLI wrapper scripts
(
echo @echo off
echo setlocal enabledelayedexpansion
echo set "SCRIPT_DIR=%%~dp0"
echo for %%%%f in ^("%%SCRIPT_DIR%%retromod-*-cli.jar"^) do ^(set "CLI_JAR=%%%%f" ^& goto :found^)
echo for %%%%f in ^("%%SCRIPT_DIR%%CLI\retromod-*-cli.jar"^) do ^(set "CLI_JAR=%%%%f" ^& goto :found^)
echo for %%%%f in ^("retromod-*-cli.jar"^) do ^(set "CLI_JAR=%%%%f" ^& goto :found^)
echo for %%%%f in ^("dist\retromod-*-cli.jar"^) do ^(set "CLI_JAR=%%%%f" ^& goto :found^)
echo for %%%%f in ^("dist\CLI\retromod-*-cli.jar"^) do ^(set "CLI_JAR=%%%%f" ^& goto :found^)
echo echo Error: Could not find retromod CLI JAR.
echo exit /b 1
echo :found
echo java -jar "%%CLI_JAR%%" %%*
) > dist\retromod.bat
echo   + CLI wrapper: dist\retromod.bat

echo.
echo Build complete
echo.
echo Output files in dist\:
dir /b dist\
echo.
echo Usage:
echo   CLI:      retromod ^<command^>  (or: java -jar retromod-%VERSION%-cli.jar ^<command^>)
echo   Fabric:   Drop retromod-%VERSION%-fabric.jar in mods\
echo   NeoForge: Drop retromod-%VERSION%-neoforge.jar in mods\
echo.
echo To use 'retromod' from anywhere, add dist\ to your PATH.
