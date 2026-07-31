#!/bin/bash
# Retromod build script
# Copyright (c) 2026 RevivalSMP. MIT License.
#
# Builds the CLI and loader-specific jars.

set -e

VERSION="1.2.0"

echo "Retromod build ${VERSION}"
echo

# Check the required build tools before doing any work.
if ! command -v mvn &> /dev/null; then
    echo "Maven was not found. Install it from https://maven.apache.org/install.html"
    exit 1
fi

# Java 25 is needed to compile against current Minecraft classes. Maven still
# emits Java 17 bytecode so the same Retromod jar can run on older hosts.
if command -v java &> /dev/null; then
    JAVA_VER=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VER" -lt 25 ] 2>/dev/null; then
        echo "Java 25 or later is required. Found Java $JAVA_VER."
        echo "Install from: https://adoptium.net/"
        exit 1
    fi
    echo "Using Java $JAVA_VER"
else
    echo "ERROR: Java not found! Java 25+ is required."
    echo "Install from: https://adoptium.net/"
    exit 1
fi

echo "Building Retromod..."
echo ""

# Clean and compile
echo "[1/5] Cleaning previous build..."
mvn clean -q

echo "[2/5] Compiling source code..."
mvn compile -q

echo "[3/5] Running tests..."
mvn test -q || echo "Warning: Some tests failed, continuing..."

echo "[4/5] Packaging JARs..."
mvn package -q -DskipTests

echo "[5/5] Creating distribution packages..."

# Create dist folder
mkdir -p dist

# The shaded JAR (with all dependencies) is our base for everything
SHADED_JAR="target/retromod-${VERSION}-all.jar"
if [ ! -f "$SHADED_JAR" ]; then
    echo "ERROR: Shaded JAR not found at $SHADED_JAR"
    echo "Make sure maven-shade-plugin ran successfully."
    exit 1
fi

cp "$SHADED_JAR" "dist/retromod-${VERSION}-cli.jar"
echo "  ✓ CLI tool: dist/retromod-${VERSION}-cli.jar"

# Create Fabric mod JAR (includes fabric.mod.json, excludes neoforge stuff)
echo "  Creating Fabric mod..."
mkdir -p target/fabric-build
cd target/fabric-build

# Extract the SHADED JAR (includes all dependencies)
jar xf "../retromod-${VERSION}-all.jar"

# Remove NeoForge-specific files
rm -rf META-INF/neoforge.mods.toml 2>/dev/null || true
rm -rf com/retromod/core/RetromodNeoForge.class 2>/dev/null || true

# Repackage as Fabric mod
jar cfm "../../dist/retromod-${VERSION}-fabric.jar" META-INF/MANIFEST.MF .
cd ../..
echo "  ✓ Fabric mod: dist/retromod-${VERSION}-fabric.jar"

# Create NeoForge mod JAR (includes neoforge.mods.toml, excludes fabric stuff)
echo "  Creating NeoForge mod..."
mkdir -p target/neoforge-build
cd target/neoforge-build

# Extract the SHADED JAR (includes all dependencies)
jar xf "../retromod-${VERSION}-all.jar"

# Remove Fabric-specific files
rm -f fabric.mod.json 2>/dev/null || true

# Update manifest for NeoForge
cat > META-INF/MANIFEST.MF << EOF
Manifest-Version: 1.0
Implementation-Title: Retromod
Implementation-Version: ${VERSION}
Automatic-Module-Name: retromod
EOF

# Repackage as NeoForge mod
jar cfm "../../dist/retromod-${VERSION}-neoforge.jar" META-INF/MANIFEST.MF .
cd ../..
echo "  ✓ NeoForge mod: dist/retromod-${VERSION}-neoforge.jar"

# Copy icon to dist
if [ -f "assets/icon_512.png" ]; then
    cp assets/icon_512.png dist/
fi

# Create CLI wrapper scripts
cat > "dist/retromod" << 'WRAPPER'
#!/bin/bash
# Retromod CLI wrapper - use "retromod" instead of "java -jar retromod-cli.jar"
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
find_jar() {
    for jar in "$SCRIPT_DIR"/retromod-*-cli.jar; do [ -f "$jar" ] && echo "$jar" && return; done
    for jar in "$SCRIPT_DIR"/CLI/retromod-*-cli.jar; do [ -f "$jar" ] && echo "$jar" && return; done
    for jar in retromod-*-cli.jar; do [ -f "$jar" ] && echo "$jar" && return; done
    for jar in dist/retromod-*-cli.jar dist/CLI/retromod-*-cli.jar; do [ -f "$jar" ] && echo "$jar" && return; done
    return 1
}
CLI_JAR=$(find_jar 2>/dev/null)
if [ -z "$CLI_JAR" ]; then
    echo "Error: Could not find retromod CLI JAR."
    echo "Make sure retromod-*-cli.jar is next to this script or in the current directory."
    exit 1
fi
exec java -jar "$CLI_JAR" "$@"
WRAPPER
chmod +x "dist/retromod"
echo "  ✓ CLI wrapper: dist/retromod (macOS/Linux)"

cat > "dist/retromod.bat" << 'WRAPPER'
@echo off
setlocal enabledelayedexpansion
set "SCRIPT_DIR=%~dp0"
for %%f in ("%SCRIPT_DIR%retromod-*-cli.jar") do (set "CLI_JAR=%%f" & goto :found)
for %%f in ("%SCRIPT_DIR%CLI\retromod-*-cli.jar") do (set "CLI_JAR=%%f" & goto :found)
for %%f in ("retromod-*-cli.jar") do (set "CLI_JAR=%%f" & goto :found)
for %%f in ("dist\retromod-*-cli.jar") do (set "CLI_JAR=%%f" & goto :found)
for %%f in ("dist\CLI\retromod-*-cli.jar") do (set "CLI_JAR=%%f" & goto :found)
echo Error: Could not find retromod CLI JAR.
exit /b 1
:found
java -jar "%CLI_JAR%" %*
WRAPPER
echo "  CLI wrapper: dist/retromod.bat (Windows)"

echo
echo "Build complete"
echo
echo "Output files in dist/:"
ls -lh dist/
echo ""
echo "Usage:"
echo "  CLI:      retromod <command>  (or: java -jar dist/retromod-${VERSION}-cli.jar <command>)"
echo "  Fabric:   Drop dist/retromod-${VERSION}-fabric.jar in mods/"
echo "  NeoForge: Drop dist/retromod-${VERSION}-neoforge.jar in mods/"
echo ""
echo "To add 'retromod' to your PATH:"
echo "  export PATH=\"\$PATH:$(pwd)/dist\""
