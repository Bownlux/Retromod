@echo off
REM Retromod multi-version release builder for Windows.
REM Builds Fabric, Forge, NeoForge, and the standalone CLI.

setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "VERSION=1.3.0-snapshot.10"
set "MC_VERSIONS=1.20 1.20.1 1.20.2 1.20.3 1.20.4 1.20.5 1.20.6 1.21 1.21.1 1.21.2 1.21.3 1.21.4 1.21.5 1.21.6 1.21.7 1.21.8 1.21.9 1.21.10 1.21.11 26.1 26.1.1 26.1.2 26.2"
set "LOADERS=fabric forge neoforge"
set "EXPECTED_FABRIC=23"
set "EXPECTED_FORGE=23"
set "EXPECTED_NEOFORGE=22"
set "EXPECTED_CLI=1"
set "EXPECTED_TOTAL=69"

set "SKIP_BUILD=0"
set "REQUIRE_SELF_HASH=0"

:parse_args
if "%~1"=="" goto args_done
if /I "%~1"=="--skip-build" set "SKIP_BUILD=1"
if /I "%~1"=="--require-self-hash" set "REQUIRE_SELF_HASH=1"
shift
goto parse_args

:args_done
echo Retromod multi-version build %VERSION%
echo.

where mvn >nul 2>nul
if errorlevel 1 (
    echo ERROR: Maven was not found.
    exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
    echo ERROR: Java 25 or later is required.
    exit /b 1
)
for /f "tokens=3" %%V in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VERSION_TEXT=%%~V"
for /f "tokens=1 delims=." %%V in ("!JAVA_VERSION_TEXT!") do set "JAVA_MAJOR=%%V"
if not defined JAVA_MAJOR (
    echo ERROR: Could not determine the Java version.
    exit /b 1
)
if !JAVA_MAJOR! LSS 25 (
    echo ERROR: Java 25 or later is required. Found Java !JAVA_MAJOR!.
    exit /b 1
)

where javap >nul 2>nul
if errorlevel 1 (
    echo ERROR: javap was not found. Use a complete JDK 25 installation.
    exit /b 1
)
where jar >nul 2>nul
if errorlevel 1 (
    echo ERROR: jar was not found. Use a complete JDK 25 installation.
    exit /b 1
)

set "PYTHON_CMD="
where python >nul 2>nul
if not errorlevel 1 set "PYTHON_CMD=python"
if not defined PYTHON_CMD (
    where py >nul 2>nul
    if not errorlevel 1 set "PYTHON_CMD=py -3"
)
if not defined PYTHON_CMD (
    echo ERROR: Python 3 was not found.
    exit /b 1
)

if "%SKIP_BUILD%"=="0" (
    echo [Step 1/4] Building the shaded base JAR...
    call mvn clean package -DskipTests -Dexec.skip=true
    if errorlevel 1 (
        echo ERROR: Maven build failed.
        exit /b 1
    )
) else (
    echo [Step 1/4] Skipping Maven build ^(--skip-build^).
)

set "SHADED_JAR=target\retromod-%VERSION%-all.jar"
if not exist "%SHADED_JAR%" (
    echo ERROR: Expected shaded JAR not found: %SHADED_JAR%
    exit /b 1
)

set "POM_VERSION_FILE=%TEMP%\retromod-pom-version-%RANDOM%-%RANDOM%.txt"
call mvn help:evaluate -Dexpression=project.version -q -DforceStdout > "!POM_VERSION_FILE!"
if errorlevel 1 (
    del /q "!POM_VERSION_FILE!" >nul 2>nul
    echo ERROR: Could not read the project version from pom.xml.
    exit /b 1
)
set "POM_VERSION="
for /f "usebackq delims=" %%V in ("!POM_VERSION_FILE!") do if not defined POM_VERSION set "POM_VERSION=%%V"
del /q "!POM_VERSION_FILE!" >nul 2>nul
if not "%VERSION%"=="!POM_VERSION!" (
    echo ERROR: build-all.bat VERSION ^(%VERSION%^) does not match pom.xml ^(!POM_VERSION!^).
    exit /b 1
)

set "HASH_FOUND=0"
set "EMBEDDED_SELF_HASH="
for /f "tokens=7" %%H in ('javap -classpath "%SHADED_JAR%" -p -constants com.retromod.security.SignatureVerifier 2^>nul ^| findstr /C:"EXPECTED_SELF_HASH"') do (
    set "HASH_FOUND=1"
    set "EMBEDDED_SELF_HASH=%%H"
)
if "!HASH_FOUND!"=="0" (
    echo ERROR: Could not read EXPECTED_SELF_HASH from %SHADED_JAR%.
    exit /b 1
)
set EMBEDDED_SELF_HASH=!EMBEDDED_SELF_HASH:"=!
set EMBEDDED_SELF_HASH=!EMBEDDED_SELF_HASH:;=!

set "COMPUTED_SELF_HASH="
for /f "usebackq delims=" %%H in (`!PYTHON_CMD! scripts\compute-self-hash.py "%SHADED_JAR%"`) do set "COMPUTED_SELF_HASH=%%H"
if not defined COMPUTED_SELF_HASH (
    echo ERROR: Could not compute the self-hash for %SHADED_JAR%.
    exit /b 1
)
if defined EMBEDDED_SELF_HASH if /I not "!EMBEDDED_SELF_HASH!"=="!COMPUTED_SELF_HASH!" (
    echo ERROR: Embedded self-hash does not match the shaded JAR.
    echo   embedded: !EMBEDDED_SELF_HASH!
    echo   computed: !COMPUTED_SELF_HASH!
    exit /b 1
)
if "%REQUIRE_SELF_HASH%"=="1" if not defined EMBEDDED_SELF_HASH (
    echo ERROR: --require-self-hash was set, but the build has no embedded self-hash.
    exit /b 1
)
if defined EMBEDDED_SELF_HASH (
    echo   Self-hash: !EMBEDDED_SELF_HASH! ^(verified^)
) else (
    echo   Self-hash: development build ^(not embedded^)
)

REM Refuse linked or redirected release trees before cleanup or packaging.
!PYTHON_CMD! -c "import os; from pathlib import Path; p=Path('dist'); linked=lambda q:q.is_symlink() or bool(getattr(q.lstat(),'st_reparse_tag',0)); bad=(p.exists() and not p.is_dir()) or ((p.exists() or p.is_symlink()) and linked(p)) or (p.is_dir() and any(linked(Path(root)/name) for root,dirs,files in os.walk(p,followlinks=False) for name in dirs+files)); raise SystemExit(1 if bad else 0)"
if errorlevel 1 (
    echo ERROR: dist must be a real tree without linked or redirected paths.
    exit /b 1
)

REM Remove only generated Retromod jars after every preflight has passed.
set "STALE_DELETE_FAILED=0"
if exist dist (
    for /r dist %%F in (retromod-*.jar) do if exist "%%F" (
        del /q "%%F" >nul 2>nul
        if exist "%%F" set "STALE_DELETE_FAILED=1"
    )
)
if "!STALE_DELETE_FAILED!"=="1" (
    echo ERROR: Could not remove every stale Retromod JAR from dist\.
    exit /b 1
)
if not exist dist\Fabric mkdir dist\Fabric
if not exist dist\Forge mkdir dist\Forge
if not exist dist\NeoForge mkdir dist\NeoForge
if not exist dist\CLI mkdir dist\CLI

echo.
echo [Step 2/4] Creating the CLI artifact...
set "CLI_OUTPUT=dist\CLI\retromod-%VERSION%-cli.jar"
if exist "!CLI_OUTPUT!" del /q "!CLI_OUTPUT!" >nul 2>nul
if exist "!CLI_OUTPUT!" (
    echo ERROR: Could not remove the stale CLI artifact.
    exit /b 1
)
copy /Y "%SHADED_JAR%" "!CLI_OUTPUT!" >nul
if errorlevel 1 (
    del /q "!CLI_OUTPUT!" >nul 2>nul
    echo ERROR: Could not create the CLI artifact.
    exit /b 1
)
!PYTHON_CMD! -c "import hashlib,sys,zipfile; from pathlib import Path; s,o=map(Path,sys.argv[1:]); same=s.stat().st_size==o.stat().st_size and hashlib.sha256(s.read_bytes()).digest()==hashlib.sha256(o.read_bytes()).digest(); z=zipfile.ZipFile(o); bad=z.testzip(); z.close(); raise SystemExit(0 if same and bad is None else 1)" "%SHADED_JAR%" "!CLI_OUTPUT!"
if errorlevel 1 (
    del /q "!CLI_OUTPUT!" >nul 2>nul
    echo ERROR: CLI artifact validation failed.
    exit /b 1
)

echo.
echo [Step 3/4] Creating loader-specific artifacts...
set "FAILED=0"
for %%L in (%LOADERS%) do (
    echo Building %%L JARs...
    for %%V in (%MC_VERSIONS%) do (
        call :create_mod_jar %%L %%V
        if errorlevel 1 set /a FAILED+=1
    )
)

echo.
echo [Step 4/4] Copying release assets...
if exist assets\icon_512.png copy /Y assets\icon_512.png dist\ >nul
if exist assets\icon_128.png copy /Y assets\icon_128.png dist\ >nul

call :count_jars dist\Fabric FABRIC_COUNT
call :count_jars dist\Forge FORGE_COUNT
call :count_jars dist\NeoForge NEOFORGE_COUNT
call :count_jars dist\CLI CLI_COUNT
set /a TOTAL_COUNT=FABRIC_COUNT+FORGE_COUNT+NEOFORGE_COUNT+CLI_COUNT

echo.
echo Summary:
echo   Fabric:   !FABRIC_COUNT! JARs
echo   Forge:    !FORGE_COUNT! JARs
echo   NeoForge: !NEOFORGE_COUNT! JARs
echo   CLI:      !CLI_COUNT! JAR
echo   Total:    !TOTAL_COUNT! JARs

set "RELEASE_OK=1"
if not "!FABRIC_COUNT!"=="%EXPECTED_FABRIC%" (
    echo ERROR: Fabric produced !FABRIC_COUNT! JARs, expected %EXPECTED_FABRIC%.
    set "RELEASE_OK=0"
)
if not "!FORGE_COUNT!"=="%EXPECTED_FORGE%" (
    echo ERROR: Forge produced !FORGE_COUNT! JARs, expected %EXPECTED_FORGE%.
    set "RELEASE_OK=0"
)
if not "!NEOFORGE_COUNT!"=="%EXPECTED_NEOFORGE%" (
    echo ERROR: NeoForge produced !NEOFORGE_COUNT! JARs, expected %EXPECTED_NEOFORGE%.
    set "RELEASE_OK=0"
)
if not "!CLI_COUNT!"=="%EXPECTED_CLI%" (
    echo ERROR: CLI produced !CLI_COUNT! JARs, expected %EXPECTED_CLI%.
    set "RELEASE_OK=0"
)
if not "!TOTAL_COUNT!"=="%EXPECTED_TOTAL%" (
    echo ERROR: Build produced !TOTAL_COUNT! JARs, expected %EXPECTED_TOTAL%.
    set "RELEASE_OK=0"
)
if not "!FAILED!"=="0" (
    echo ERROR: !FAILED! loader artifacts failed to build.
    set "RELEASE_OK=0"
)
if "!RELEASE_OK!"=="0" exit /b 1

set "CHECKSUM_COUNT_FILE=%TEMP%\retromod-checksum-count-%RANDOM%-%RANDOM%.txt"
!PYTHON_CMD! scripts\generate-release-checksums.py --version "%VERSION%" --dist dist > "!CHECKSUM_COUNT_FILE!"
if errorlevel 1 (
    del /q "!CHECKSUM_COUNT_FILE!" >nul 2>nul
    echo ERROR: Could not generate dist\SHA256SUMS.txt.
    exit /b 1
)

set "CHECKSUM_COUNT="
for /f "usebackq delims=" %%C in ("!CHECKSUM_COUNT_FILE!") do if not defined CHECKSUM_COUNT set "CHECKSUM_COUNT=%%C"
del /q "!CHECKSUM_COUNT_FILE!" >nul 2>nul
if not "!CHECKSUM_COUNT!"=="%EXPECTED_TOTAL%" (
    echo ERROR: SHA256SUMS.txt has !CHECKSUM_COUNT! entries, expected %EXPECTED_TOTAL%.
    exit /b 1
)

echo.
echo dist looks complete: !TOTAL_COUNT! JARs with !CHECKSUM_COUNT! checksums.
exit /b 0

:create_mod_jar
set "LOADER=%~1"
set "MC_VERSION=%~2"

if /I "%LOADER%"=="neoforge" if "%MC_VERSION%"=="1.20" exit /b 0

set "LOADER_DIR="
if /I "%LOADER%"=="fabric" set "LOADER_DIR=Fabric"
if /I "%LOADER%"=="forge" set "LOADER_DIR=Forge"
if /I "%LOADER%"=="neoforge" set "LOADER_DIR=NeoForge"
if not defined LOADER_DIR exit /b 1

call :set_version_requirements "%MC_VERSION%"

set "OUTPUT_NAME=retromod-%VERSION%+%MC_VERSION%.jar"
set "OUTPUT_DIR=%CD%\dist\%LOADER_DIR%\%MC_VERSION%"
set "OUTPUT_PATH=!OUTPUT_DIR!\!OUTPUT_NAME!"
if not exist "!OUTPUT_DIR!" mkdir "!OUTPUT_DIR!"

set "TEMP_DIR=%TEMP%\retromod-build-%LOADER%-%MC_VERSION:.=_%-%RANDOM%-%RANDOM%"
if exist "!TEMP_DIR!" rmdir /s /q "!TEMP_DIR!"
mkdir "!TEMP_DIR!"
if errorlevel 1 exit /b 1

pushd "!TEMP_DIR!"
jar xf "%~dp0%SHADED_JAR%"
if errorlevel 1 (
    popd
    rmdir /s /q "!TEMP_DIR!"
    echo ERROR: Could not extract %SHADED_JAR% for %LOADER% %MC_VERSION%.
    exit /b 1
)

if exist org\objectweb\asm rmdir /s /q org\objectweb\asm
if exist org\objectweb\asm (
    popd
    rmdir /s /q "!TEMP_DIR!"
    echo ERROR: Could not strip bundled ASM for !LOADER! !MC_VERSION!.
    exit /b 1
)
if /I not "%LOADER%"=="fabric" if exist javax\annotation rmdir /s /q javax\annotation
if /I not "%LOADER%"=="fabric" if exist javax\annotation (
    popd
    rmdir /s /q "!TEMP_DIR!"
    echo ERROR: Could not strip javax.annotation for !LOADER! !MC_VERSION!.
    exit /b 1
)

if /I "%LOADER%"=="fabric" (
    set "METADATA_DELETE_FAILED=0"
    for %%M in (META-INF\neoforge.mods.toml META-INF\mods.toml pack.mcmeta) do (
        if exist "%%M" del /q "%%M" >nul 2>nul
        if exist "%%M" set "METADATA_DELETE_FAILED=1"
    )
    if "!METADATA_DELETE_FAILED!"=="1" (
        popd
        rmdir /s /q "!TEMP_DIR!"
        echo ERROR: Could not remove non-Fabric metadata for !MC_VERSION!.
        exit /b 1
    )
    !PYTHON_CMD! -c "import json,sys; from pathlib import Path; f,q,mc,j,v=sys.argv[1:]; fp=Path(f); qp=Path(q); fd=json.loads(fp.read_text(encoding='utf-8')); fd['depends']['minecraft']=mc; fd['depends']['java']=j; fd['version']=v; fp.write_text(json.dumps(fd,indent=2),encoding='utf-8'); qd=json.loads(qp.read_text(encoding='utf-8')); ql=qd['quilt_loader']; deps=ql['depends']; qm=next((x for x in deps if isinstance(x,dict) and x.get('id')=='minecraft'),None); qm.__setitem__('versions','='+mc) if qm else deps.append({'id':'minecraft','versions':'='+mc}); qj=next((x for x in deps if isinstance(x,dict) and x.get('id')=='java'),None); qj.__setitem__('versions',j) if qj else deps.append({'id':'java','versions':j}); ql['version']=v; qp.write_text(json.dumps(qd,indent=2),encoding='utf-8')" "fabric.mod.json" "quilt.mod.json" "%MC_VERSION%" "!JAVA_REQ!" "%VERSION%"
    if errorlevel 1 (
        popd
        rmdir /s /q "!TEMP_DIR!"
        echo ERROR: Could not update Fabric and Quilt metadata for %MC_VERSION%.
        exit /b 1
    )
)

if /I "%LOADER%"=="forge" (
    set "METADATA_DELETE_FAILED=0"
    for %%M in (fabric.mod.json quilt.mod.json META-INF\neoforge.mods.toml) do (
        if exist "%%M" del /q "%%M" >nul 2>nul
        if exist "%%M" set "METADATA_DELETE_FAILED=1"
    )
    if "!METADATA_DELETE_FAILED!"=="1" (
        popd
        rmdir /s /q "!TEMP_DIR!"
        echo ERROR: Could not remove non-Forge metadata for !MC_VERSION!.
        exit /b 1
    )
    if not exist META-INF mkdir META-INF
    if not exist META-INF (
        popd
        rmdir /s /q "!TEMP_DIR!"
        echo ERROR: Could not prepare Forge metadata for !MC_VERSION!.
        exit /b 1
    )
    call :write_forge_toml "%TEMP_DIR%\META-INF\mods.toml"
    if errorlevel 1 (
        popd
        rmdir /s /q "!TEMP_DIR!"
        echo ERROR: Could not write Forge metadata for !MC_VERSION!.
        exit /b 1
    )
)

if /I "%LOADER%"=="neoforge" (
    set "METADATA_DELETE_FAILED=0"
    for %%M in (fabric.mod.json quilt.mod.json META-INF\mods.toml) do (
        if exist "%%M" del /q "%%M" >nul 2>nul
        if exist "%%M" set "METADATA_DELETE_FAILED=1"
    )
    if "!METADATA_DELETE_FAILED!"=="1" (
        popd
        rmdir /s /q "!TEMP_DIR!"
        echo ERROR: Could not remove non-NeoForge metadata for !MC_VERSION!.
        exit /b 1
    )
    if not exist META-INF mkdir META-INF
    if not exist META-INF (
        popd
        rmdir /s /q "!TEMP_DIR!"
        echo ERROR: Could not prepare NeoForge metadata for !MC_VERSION!.
        exit /b 1
    )
    call :write_neoforge_toml "%TEMP_DIR%\META-INF\neoforge.mods.toml"
    if errorlevel 1 (
        popd
        rmdir /s /q "!TEMP_DIR!"
        echo ERROR: Could not write NeoForge metadata for !MC_VERSION!.
        exit /b 1
    )
)

if not exist META-INF mkdir META-INF
(
    echo Manifest-Version: 1.0
    echo Implementation-Title: Retromod
    echo Implementation-Version: %VERSION%
    echo Retromod-Target-MC: %MC_VERSION%
    echo Retromod-Loader: %LOADER%
    echo Automatic-Module-Name: retromod
    echo.
) > META-INF\MANIFEST.MF
if errorlevel 1 (
    popd
    rmdir /s /q "!TEMP_DIR!"
    echo ERROR: Could not write the manifest for !LOADER! !MC_VERSION!.
    exit /b 1
)
if not exist META-INF\MANIFEST.MF (
    popd
    rmdir /s /q "!TEMP_DIR!"
    echo ERROR: Manifest was not created for !LOADER! !MC_VERSION!.
    exit /b 1
)

if exist "!OUTPUT_PATH!" del /q "!OUTPUT_PATH!" >nul 2>nul
if exist "!OUTPUT_PATH!" (
    popd
    rmdir /s /q "!TEMP_DIR!"
    echo ERROR: Could not remove stale output !OUTPUT_PATH!.
    exit /b 1
)
jar cfm "!OUTPUT_PATH!" META-INF\MANIFEST.MF .
if errorlevel 1 (
    popd
    rmdir /s /q "!TEMP_DIR!"
    echo ERROR: Could not create !OUTPUT_NAME!.
    exit /b 1
)

call :validate_loader_jar "!OUTPUT_PATH!" "!LOADER!" "!MC_VERSION!" "%VERSION%" "!JAVA_REQ!"
if errorlevel 1 (
    del /q "!OUTPUT_PATH!" >nul 2>nul
    popd
    rmdir /s /q "!TEMP_DIR!"
    echo ERROR: Packaged JAR validation failed for !LOADER! !MC_VERSION!.
    exit /b 1
)

popd
rmdir /s /q "!TEMP_DIR!"
echo   %LOADER% %MC_VERSION%
exit /b 0

:set_version_requirements
set "MC_VERSION=%~1"
set "JAVA_REQ=>=17"
if "%MC_VERSION%"=="1.20.5" set "JAVA_REQ=>=21"
if "%MC_VERSION%"=="1.20.6" set "JAVA_REQ=>=21"
if "%MC_VERSION:~0,4%"=="1.21" set "JAVA_REQ=>=21"
if "%MC_VERSION:~0,3%"=="26." set "JAVA_REQ=>=25"

set "FORGE_LV=40"
if "%MC_VERSION%"=="1.20" set "FORGE_LV=47"
if "%MC_VERSION%"=="1.20.1" set "FORGE_LV=47"
if "%MC_VERSION%"=="1.20.2" set "FORGE_LV=48"
if "%MC_VERSION%"=="1.20.3" set "FORGE_LV=49"
if "%MC_VERSION%"=="1.20.4" set "FORGE_LV=49"
if "%MC_VERSION%"=="1.20.5" set "FORGE_LV=50"
if "%MC_VERSION%"=="1.20.6" set "FORGE_LV=50"
if "%MC_VERSION%"=="1.21" set "FORGE_LV=51"
if "%MC_VERSION%"=="1.21.1" set "FORGE_LV=52"
if "%MC_VERSION%"=="1.21.2" set "FORGE_LV=52"
if "%MC_VERSION%"=="1.21.3" set "FORGE_LV=53"
if "%MC_VERSION%"=="1.21.4" set "FORGE_LV=54"
if "%MC_VERSION%"=="1.21.5" set "FORGE_LV=55"
if "%MC_VERSION%"=="1.21.6" set "FORGE_LV=56"
if "%MC_VERSION%"=="1.21.7" set "FORGE_LV=57"
if "%MC_VERSION%"=="1.21.8" set "FORGE_LV=57"
if "%MC_VERSION%"=="1.21.9" set "FORGE_LV=58"
if "%MC_VERSION%"=="1.21.10" set "FORGE_LV=58"
if "%MC_VERSION%"=="1.21.11" set "FORGE_LV=58"
if "%MC_VERSION%"=="26.1" set "FORGE_LV=64"
if "%MC_VERSION%"=="26.1.1" set "FORGE_LV=64"
if "%MC_VERSION%"=="26.1.2" set "FORGE_LV=64"
if "%MC_VERSION%"=="26.2" set "FORGE_LV=65"

set "NEOFORGE_LV=20"
if "%MC_VERSION%"=="1.20.1" set "NEOFORGE_LV=47"
if "%MC_VERSION%"=="1.20.2" set "NEOFORGE_LV=20.2"
if "%MC_VERSION%"=="1.20.3" set "NEOFORGE_LV=20.3"
if "%MC_VERSION%"=="1.20.4" set "NEOFORGE_LV=20.4"
if "%MC_VERSION%"=="1.20.5" set "NEOFORGE_LV=20.5"
if "%MC_VERSION%"=="1.20.6" set "NEOFORGE_LV=20.6"
if "%MC_VERSION%"=="1.21" set "NEOFORGE_LV=21.0"
if "%MC_VERSION%"=="1.21.1" set "NEOFORGE_LV=21.1"
if "%MC_VERSION%"=="1.21.2" set "NEOFORGE_LV=21.2"
if "%MC_VERSION%"=="1.21.3" set "NEOFORGE_LV=21.3"
if "%MC_VERSION%"=="1.21.4" set "NEOFORGE_LV=21.4"
if "%MC_VERSION%"=="1.21.5" set "NEOFORGE_LV=21.5"
if "%MC_VERSION%"=="1.21.6" set "NEOFORGE_LV=21.6"
if "%MC_VERSION%"=="1.21.7" set "NEOFORGE_LV=21.7"
if "%MC_VERSION%"=="1.21.8" set "NEOFORGE_LV=21.8"
if "%MC_VERSION%"=="1.21.9" set "NEOFORGE_LV=21.9"
if "%MC_VERSION%"=="1.21.10" set "NEOFORGE_LV=21.10"
if "%MC_VERSION%"=="1.21.11" set "NEOFORGE_LV=21.11"
if "%MC_VERSION%"=="26.1" set "NEOFORGE_LV=26.1"
if "%MC_VERSION%"=="26.1.1" set "NEOFORGE_LV=26.1"
if "%MC_VERSION%"=="26.1.2" set "NEOFORGE_LV=26.1"
if "%MC_VERSION%"=="26.2" set "NEOFORGE_LV=26.2.0.0-beta"
exit /b 0

:write_forge_toml
(
    echo modLoader = "javafml"
    echo loaderVersion = "[!FORGE_LV!,^)"
    echo license = "MIT"
    echo issueTrackerURL = "https://github.com/Bownlux/Retromod/issues"
    echo.
    echo [[mods]]
    echo modId = "retromod"
    echo version = "%VERSION%"
    echo displayName = "Retromod"
    echo description = '''
    echo Retromod helps older Forge mods run on Minecraft !MC_VERSION!.
    echo It updates bytecode, mixins, and loader metadata, then keeps a backup.
    echo '''
    echo authors = "Bownlux"
    echo logoFile = "assets/retromod/icon.png"
    echo.
    echo [[dependencies.retromod]]
    echo modId = "forge"
    echo mandatory = true
    echo versionRange = "[!FORGE_LV!,^)"
    echo ordering = "NONE"
    echo side = "BOTH"
    echo.
    echo [[dependencies.retromod]]
    echo modId = "minecraft"
    echo mandatory = true
    echo versionRange = "[!MC_VERSION!]"
    echo ordering = "NONE"
    echo side = "BOTH"
) > "%~1"
if errorlevel 1 exit /b 1
if not exist "%~1" exit /b 1
exit /b 0

:write_neoforge_toml
(
    echo modLoader = "javafml"
    echo loaderVersion = "[1,^)"
    echo license = "MIT"
    echo issueTrackerURL = "https://github.com/Bownlux/Retromod/issues"
    echo.
    echo [[mods]]
    echo modId = "retromod"
    echo version = "%VERSION%"
    echo displayName = "Retromod"
    echo description = '''
    echo Retromod helps older NeoForge mods run on Minecraft !MC_VERSION!.
    echo It updates bytecode, mixins, and loader metadata, then keeps a backup.
    echo '''
    echo authors = "Bownlux"
    echo logoFile = "assets/retromod/icon.png"
    echo.
    echo [[dependencies.retromod]]
    echo modId = "neoforge"
    echo type = "required"
    echo versionRange = "[!NEOFORGE_LV!,^)"
    echo ordering = "NONE"
    echo side = "BOTH"
    echo.
    echo [[dependencies.retromod]]
    echo modId = "minecraft"
    echo type = "required"
    echo versionRange = "[!MC_VERSION!]"
    echo ordering = "NONE"
    echo side = "BOTH"
) > "%~1"
if errorlevel 1 exit /b 1
if not exist "%~1" exit /b 1
exit /b 0

:validate_loader_jar
!PYTHON_CMD! -c "import json,sys,zipfile; p,l,mc,v,j=sys.argv[1:]; z=zipfile.ZipFile(p); crc=z.testzip(); n=set(z.namelist()); e={'fabric':'fabric.mod.json','forge':'META-INF/mods.toml','neoforge':'META-INF/neoforge.mods.toml'}; expected={'fabric':{'fabric.mod.json','quilt.mod.json'},'forge':{e['forge']},'neoforge':{e['neoforge']}}; lm=set(e.values()).union({'quilt.mod.json'}); mf=z.read('META-INF/MANIFEST.MF').decode('utf-8').replace('\r','').splitlines(); data=z.read(e[l]).decode('utf-8'); f=json.loads(data) if l=='fabric' else {}; d=f.get('depends',{}) if isinstance(f,dict) else {}; quilt=json.loads(z.read('quilt.mod.json')) if l=='fabric' and 'quilt.mod.json' in n else {}; ql=quilt.get('quilt_loader',{}) if isinstance(quilt,dict) else {}; qdeps=ql.get('depends',[]) if isinstance(ql,dict) else []; qmap={x.get('id'):x.get('versions') for x in qdeps if isinstance(x,dict)}; qe=ql.get('entrypoints') if isinstance(ql,dict) else None; qexpected={'main':'com.retromod.core.Retromod','client':'com.retromod.core.RetromodClient','server':'com.retromod.core.RetromodServer','preLaunch':'com.retromod.core.RetromodPreLaunch'}; lines={x.strip() for x in data.splitlines()}; q=chr(34); checks=[(crc is None,'corrupt archive entry: '+str(crc)),(n.intersection(lm)==expected[l],'wrong loader metadata'),(not any(x.startswith('org/objectweb/asm/') for x in n),'bundled ASM remains'),(l=='fabric' or not any(x.startswith('javax/annotation/') for x in n),'bundled javax.annotation remains'),('Implementation-Version: '+v in mf,'manifest version mismatch'),('Retromod-Target-MC: '+mc in mf,'manifest Minecraft target mismatch'),('Retromod-Loader: '+l in mf,'manifest loader mismatch'),(l!='fabric' or (f.get('version')==v and d.get('minecraft')==mc and d.get('java')==j),'Fabric metadata mismatch'),(l!='fabric' or (ql.get('version')==v and qmap.get('minecraft')=='='+mc and qmap.get('java')==j),'Quilt metadata mismatch'),(l!='fabric' or qe==qexpected,'Quilt entrypoints mismatch'),(l=='fabric' or ('version = '+q+v+q in lines and 'versionRange = '+q+'['+mc+']'+q in lines and 'modId = '+q+l+q in lines),'Forge metadata mismatch')]; bad=next((message for valid,message in checks if not valid),None); z.close(); raise SystemExit(bad or 0)" "%~1" "%~2" "%~3" "%~4" "%~5"
if errorlevel 1 exit /b 1
exit /b 0

:count_jars
set "COUNT=0"
if exist "%~1" for /r "%~1" %%F in (*.jar) do set /a COUNT+=1
set "%~2=!COUNT!"
exit /b 0
