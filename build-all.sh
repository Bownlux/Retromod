#!/bin/bash
# Retromod multi-version build script
# Copyright (c) 2026 RevivalSMP. MIT License.
#
# Builds Retromod for each supported host:
#   - Fabric (1.20 through 26.2)
#   - Forge (1.20 through 26.1)
#   - NeoForge (1.20.1 through 26.2)
#   - Standalone CLI
# Older mods are translated at runtime and do not need separate host jars.

# Keep building after one target fails so the final report can list every failure.
# set -e

VERSION="1.3.0-snapshot.3"
# Older mods are translated at runtime, so only 1.20 and newer need host jars.
# Security-only updates for versions before 26.1.
MC_VERSIONS=("1.20" "1.20.1" "1.20.2" "1.20.3" "1.20.4" "1.20.5" "1.20.6" "1.21" "1.21.1" "1.21.2" "1.21.3" "1.21.4" "1.21.5" "1.21.6" "1.21.7" "1.21.8" "1.21.9" "1.21.10" "1.21.11" "26.1" "26.1.1" "26.1.2" "26.2")
LOADERS=("fabric" "forge" "neoforge")

echo "Retromod multi-version build ${VERSION}"
echo
echo "Building for:"
echo "  - ${#MC_VERSIONS[@]} Minecraft versions (1.20 - 26.2)"
echo "  - ${#LOADERS[@]} mod loaders (Fabric, Forge, NeoForge)"
echo ""

# ---- Pre-flight checks ----

# Check for Maven
if ! command -v mvn &> /dev/null; then
    echo "ERROR: Maven not found!"
    echo "Install: https://maven.apache.org/install.html"
    exit 1
fi

# Check for Java 25+
if command -v java &> /dev/null; then
    JAVA_VER=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VER" -lt 25 ] 2>/dev/null; then
        echo "ERROR: Java 25 or later is required! You have Java $JAVA_VER."
        echo "Install from: https://adoptium.net/"
        exit 1
    fi
else
    echo "ERROR: Java not found! Java 25+ is required."
    echo "Install from: https://adoptium.net/"
    exit 1
fi

# Check for unzip (needed to extract JARs)
if ! command -v unzip &> /dev/null; then
    echo "ERROR: 'unzip' is not installed!"
    echo "Install it:"
    echo "  Ubuntu/Debian: sudo apt install unzip"
    echo "  Fedora/RHEL:   sudo dnf install unzip"
    echo "  macOS:         brew install unzip (or it's usually pre-installed)"
    exit 1
fi

# Check for zip (needed to create JARs)
if ! command -v zip &> /dev/null; then
    echo "ERROR: 'zip' is not installed!"
    echo "Install it:"
    echo "  Ubuntu/Debian: sudo apt install zip"
    echo "  Fedora/RHEL:   sudo dnf install zip"
    echo "  macOS:         brew install zip (or it's usually pre-installed)"
    exit 1
fi

# Remove any stale JARs from previous builds. Without this, a version bump
# leaves the OLD version's JARs sitting alongside the new ones in
# dist/<Loader>/<MC>/, which is confusing when you go to upload to Modrinth
# (you might pick the wrong one) and bloats the dist/ tree over time.
#
# Scope: only delete files matching the retromod naming pattern. Leaves any
# user-added files (notes, scripts, custom configs) in dist/ untouched.
if [ -d dist ]; then
    find dist -name "retromod-*.jar" -type f -delete 2>/dev/null
fi

# Create output directories
mkdir -p dist/Fabric
mkdir -p dist/Forge
mkdir -p dist/NeoForge
mkdir -p dist/CLI

# Check for --skip-build flag (used when called from Maven)
SKIP_BUILD=false
for arg in "$@"; do
    if [ "$arg" = "--skip-build" ]; then
        SKIP_BUILD=true
    fi
done

if [ "$SKIP_BUILD" = false ]; then
    # Build the base JAR first
    echo "[Step 1/4] Building base JAR with Maven..."
    mvn clean package -DskipTests
else
    echo "[Step 1/4] Skipping Maven build (--skip-build flag)"
fi

# Find the shaded JAR (with all dependencies bundled)
SHADED_JAR=""
if [ -f "target/retromod-${VERSION}-all.jar" ]; then
    SHADED_JAR="target/retromod-${VERSION}-all.jar"
else
    # Fallback: find any -all.jar
    SHADED_JAR=$(find target -maxdepth 1 -name "retromod*-all.jar" | head -1)
fi

if [ -z "$SHADED_JAR" ] || [ ! -f "$SHADED_JAR" ]; then
    echo "ERROR: Shaded JAR not found in target/"
    echo "Make sure maven-shade-plugin ran successfully."
    echo "Build failed"
    exit 1
fi

echo "  Shaded JAR: $SHADED_JAR (with bundled dependencies)"

echo ""
echo "[Step 2/4] Creating CLI tool..."

# CLI is the shaded JAR directly
cp "$SHADED_JAR" "dist/CLI/retromod-${VERSION}-cli.jar"
echo "  dist/CLI/retromod-${VERSION}-cli.jar"

echo ""
echo "[Step 3/4] Creating loader-specific JARs..."

# Defensive guard: host MC versions below 1.20 never go into dist/.
# Retromod itself requires Java 25 and MC 1.20+ to run as a mod - earlier MC
# versions are only relevant as TRANSLATION SOURCES (the shim chain walks an
# old mod forward to your 1.20+ host), not as host targets. If someone later
# adds "1.19.2" or "1.16.5" to MC_VERSIONS by mistake, this filter stops it
# from producing a dist/ artifact rather than silently shipping a broken build.
is_host_mc_supported() {
    local ver=$1
    case $ver in
        # Explicit allow-list of host MC versions we ship builds for
        1.20|1.20.[0-9]*) return 0 ;;
        1.21|1.21.[0-9]*) return 0 ;;
        26.[0-9]*) return 0 ;;
        27.[0-9]*) return 0 ;;  # forward-compat
        28.[0-9]*) return 0 ;;  # forward-compat
        # Everything else (1.12 - 1.19.x, plus anything unrecognized) is rejected
        *) return 1 ;;
    esac
}

# Function to check if a loader supports a given MC version.
#
# This is more nuanced than a simple "loader started at version X" cutoff -
# NeoForge in particular skips MC patch releases. For the 26.x line NeoForge
# has only released builds for 26.1.2 (as of this writing), NOT 26.1 or
# 26.1.1. Building our own Retromod-for-NeoForge JAR that declares 26.1 or
# 26.1.1 as its MC version would produce an artifact nobody can use because
# there's no NeoForge loader for those MC patches.
#
# Keep this synced with:
#   Fabric intermediary:  https://maven.fabricmc.net/net/fabricmc/intermediary/
#   NeoForge releases:    https://maven.neoforged.net/releases/net/neoforged/neoforge/
#   Forge releases:       https://maven.minecraftforge.net/net/minecraftforge/forge/
loader_supports_version() {
    local loader=$1
    local ver=$2
    case $loader in
        neoforge)
            # Started at 1.20.1; patch-release coverage is uneven.
            case $ver in
                # Before NeoForge existed
                1.12*|1.13*|1.14*|1.15*|1.16*|1.17*|1.18*|1.19*|1.20) return 1 ;;
                # 26.x: NeoForge skipped the 26.1 and 26.1.1 patches (only 26.1.2
                # got a build); 26.2 shipped a NeoForge build, so it's allowed.
                26.1|26.1.1) return 1 ;;
                # Everything else (1.20.1+, 1.21.x, 26.1.2) is supported
                *) return 0 ;;
            esac
            ;;
        fabric)
            # Fabric started at 1.14. Fabric Loader itself is MC-version-agnostic;
            # intermediary mappings cover essentially every MC release, so we
            # don't need per-patch exceptions here.
            case $ver in
                1.12*|1.13*) return 1 ;;
                *) return 0 ;;
            esac
            ;;
        forge)
            # Forge 26.2 shipped (forge 65.x), so every listed MC version now builds.
            return 0
            ;;
        *) return 0 ;;
    esac
}

# Function to create a mod JAR for specific loader and version
create_mod_jar() {
    local LOADER=$1
    local MC_VERSION=$2
    local LOADER_DIR=""

    # Guard: never emit a dist/ artifact for a host MC below 1.20.
    # Noisy skip (log at WARN) rather than silent so a misconfigured
    # MC_VERSIONS array is visible.
    if ! is_host_mc_supported "$MC_VERSION"; then
        echo "  skip: ${LOADER} ${MC_VERSION} - host MC < 1.20 not built into dist/"
        return 0
    fi

    # Skip unsupported loader/version combinations
    if ! loader_supports_version "$LOADER" "$MC_VERSION"; then
        return 0  # Silently skip
    fi

    # Map to correct directory names
    case $LOADER in
        fabric) LOADER_DIR="Fabric" ;;
        forge) LOADER_DIR="Forge" ;;
        neoforge) LOADER_DIR="NeoForge" ;;
    esac

    local OUTPUT_NAME="retromod-${VERSION}+${MC_VERSION}.jar"
    local ORIG_DIR="$(pwd)"
    local OUTPUT_PATH="${ORIG_DIR}/dist/${LOADER_DIR}/${MC_VERSION}/${OUTPUT_NAME}"

    # Create version subdirectory
    mkdir -p "${ORIG_DIR}/dist/${LOADER_DIR}/${MC_VERSION}"

    # Create temp directory
    local TEMP_DIR=$(mktemp -d)

    # Extract shaded JAR (includes all dependencies like ASM, Gson, TOML4J)
    unzip -q "$SHADED_JAR" -d "$TEMP_DIR" 2>/dev/null || {
        echo "ERROR: Failed to extract shaded JAR"
        rm -rf "$TEMP_DIR"
        return 1
    }

    # Strip bundled ASM from the MOD jar - use the loader's ASM instead.
    #
    # This is the resolution of the ASM shading dilemma that bit beta.3/beta.4:
    #   - Relocating ASM (com.retromod.shaded.asm) broke Forge's
    #     EventSubclassTransformer, which runs on the system classloader and
    #     looks up ASM class names by string - it can't see our shaded copy.
    #     (issue #12, beta.3)
    #   - NOT relocating but still bundling ASM broke Fabric AND NeoForge with
    #     a loader-constraint violation: the loader already loaded
    #     org.objectweb.asm.tree.ClassNode from ITS bundled ASM, and our
    #     duplicate copy in the mod classloader is a second class with the
    #     same name → LinkageError. (issues #18 NeoForge, #19 Fabric, beta.4)
    #
    # Every mod loader (Fabric, Forge, NeoForge) bundles its own ASM and
    # exposes it to mods. So the mod JAR doesn't need its own copy at all -
    # stripping org/objectweb/asm/ here makes Retromod resolve ASM against
    # the loader's copy, which is exactly one consistent ASM per runtime.
    # No relocation, no duplicate, no conflict on any loader.
    #
    # The CLI keeps its bundled ASM because it runs standalone with no loader
    # to provide one - and the CLI is a direct copy of the shaded jar, it
    # never goes through this extraction/strip path.
    rm -rf "$TEMP_DIR/org/objectweb/asm" 2>/dev/null

    # Strip our javax.annotation polyfill stubs on Forge/NeoForge ONLY.
    #
    # We bundle javax/annotation/{Nullable,Nonnull}.class as polyfill stubs so
    # old mods that reference javax.annotation.* resolve even when nothing else
    # provides them. They CAN'T be relocated - a polyfill only works if it sits
    # at the real package name the mod expects.
    #
    # But Forge and NeoForge bundle Guava, which pulls in jsr305 as a JPMS
    # module that ALSO exports javax.annotation. Strict JPMS resolution then
    # refuses to start: "Modules jsr305 and retromod export package
    # javax.annotation to module mixin_synthetic" (issue #20, NeoForge 1.21.1
    # + Forge 1.20.1). On those loaders jsr305 already provides the real
    # javax.annotation, so our stub is redundant AND conflicting - strip it.
    #
    # Fabric does NOT enforce JPMS module boundaries (everything loads in the
    # knot classloader), so there's no conflict there and the stub stays as a
    # harmless fallback. Same for the standalone CLI.
    #
    # Why only javax.annotation and not our other polyfill packages
    # (baubles/api, cofh/api, codechicken/nei, mcp/mobius/waila)? jsr305 is a
    # near-universal transitive dependency so javax.annotation conflicts on
    # essentially every Forge/NeoForge install. The others are stubs for
    # ancient 1.7-1.12 mods that are basically never present as modules on
    # modern MC, so they don't collide - and stripping them would break the
    # polyfill for the rare user actually translating one of those old mods.
    if [ "$LOADER" = "forge" ] || [ "$LOADER" = "neoforge" ]; then
        rm -rf "$TEMP_DIR/javax/annotation" 2>/dev/null
    fi

    # NOTE (#90, was #78): the NeoForge mod-file locator SERVICE entry
    # (META-INF/services/...IModFileCandidateLocator) has been REMOVED from the jar.
    # Declaring it made FML claim the whole retromod jar as an early service and then
    # SKIP it for @Mod scanning, so RetromodNeoForge never constructed and the runtime
    # transform was inert on NeoForge 26.2 (confirmed in-game). Same trap as the Forge
    # #102 fix (which removed the forgespi IModLocator service). The RetromodModLocator
    # CLASS is KEPT, so com/retromod/** is identical across loaders and the one embedded
    # self-hash still matches every per-loader dist jar. CF-export loading from
    # mods/Retromod/ moves to a separate "Retromod Loader" stub jar (follow-up).

    # Clean up any now-empty package directories left behind by the strips
    # above (e.g. org/objectweb/ after asm/ is removed, javax/ after
    # annotation/ is removed). Empty dirs don't constitute a JPMS package so
    # they're harmless, but leaving dangling org/objectweb/ in the jar is
    # confusing when auditing what packages a build exports. Depth-first so
    # parent dirs that become empty are also removed.
    find "$TEMP_DIR" -type d -empty -delete 2>/dev/null

    # Per-MC-version Java requirement. Retromod's compiled bytecode targets
    # Java 17 so the same JAR runs on Java 17, 21, and 25 - but the fabric.mod.json
    # "java" constraint is set per-MC-version so the loader rejects users running
    # the wrong Java for their MC version (e.g. MC 26.1 with Java 21 won't work
    # because MC 26.1's own class files are Java 25 bytecode).
    case $MC_VERSION in
        1.20|1.20.[1-4])    JAVA_REQ=">=17" ;;   # MC 1.20 - 1.20.4: Java 17
        1.20.[5-9]|1.20.1[0-9]) JAVA_REQ=">=21" ;;   # MC 1.20.5+ bumped to Java 21
        1.21*)              JAVA_REQ=">=21" ;;   # All of 1.21.x: Java 21
        26.*|27.*|28.*)     JAVA_REQ=">=25" ;;   # MC 26.1+ bumped to Java 25
        *)                  JAVA_REQ=">=17" ;;   # Conservative fallback
    esac

    # Per-MC Forge loader minimum. Each MC version ships a specific Forge
    # loader number; we previously hardcoded "[52,)" which silently rejected
    # every Forge build for MC 1.20.x (Forge 47-50) - our Forge JARs for
    # those MC versions wouldn't actually load. Setting this per-MC fixes
    # the mismatch.
    case $MC_VERSION in
        1.20|1.20.1)            FORGE_LV="47" ;;
        1.20.2)                 FORGE_LV="48" ;;
        1.20.3|1.20.4)          FORGE_LV="49" ;;
        1.20.5|1.20.6)          FORGE_LV="50" ;;
        1.21|1.21.0)            FORGE_LV="51" ;;
        1.21.1|1.21.2)          FORGE_LV="52" ;;
        1.21.3)                 FORGE_LV="53" ;;
        1.21.4)                 FORGE_LV="54" ;;
        1.21.5)                 FORGE_LV="55" ;;
        1.21.6)                 FORGE_LV="56" ;;
        1.21.7|1.21.8)          FORGE_LV="57" ;;
        1.21.9|1.21.10|1.21.11) FORGE_LV="58" ;;
        26.2*)                  FORGE_LV="65" ;;   # Forge 26.2 → 65.x
        26.*)                   FORGE_LV="64" ;;   # Forge 26.1.2 → 64.x
        *)                      FORGE_LV="40" ;;  # Permissive fallback
    esac

    # Per-MC NeoForge loader minimum. NeoForge's versioning mostly mirrors
    # the MC version (NeoForge X.Y.Z for MC X.Y), with 1.20.1 as the legacy
    # 47.x outlier (NeoForge inherited Forge's version scheme for that
    # initial MC version). Previously hardcoded "[4,)" - too low.
    case $MC_VERSION in
        1.20|1.20.1)            NEOFORGE_LV="47" ;;
        1.20.2)                 NEOFORGE_LV="20.2" ;;
        1.20.3)                 NEOFORGE_LV="20.3" ;;
        1.20.4)                 NEOFORGE_LV="20.4" ;;
        1.20.5)                 NEOFORGE_LV="20.5" ;;
        1.20.6)                 NEOFORGE_LV="20.6" ;;
        1.21|1.21.0)            NEOFORGE_LV="21.0" ;;
        1.21.1)                 NEOFORGE_LV="21.1" ;;
        1.21.2)                 NEOFORGE_LV="21.2" ;;
        1.21.3)                 NEOFORGE_LV="21.3" ;;
        1.21.4)                 NEOFORGE_LV="21.4" ;;
        1.21.5)                 NEOFORGE_LV="21.5" ;;
        1.21.6)                 NEOFORGE_LV="21.6" ;;
        1.21.7)                 NEOFORGE_LV="21.7" ;;
        1.21.8)                 NEOFORGE_LV="21.8" ;;
        1.21.9)                 NEOFORGE_LV="21.9" ;;
        1.21.10)                NEOFORGE_LV="21.10" ;;
        1.21.11)                NEOFORGE_LV="21.11" ;;
        26.1*)                  NEOFORGE_LV="26.1" ;;
        # 26.2: NeoForge has only shipped a PRE-RELEASE so far (26.2.0.0-beta).
        # Maven version ordering sorts a "-beta" qualifier BELOW the bare release,
        # so a range of "[26.2,)" does NOT match 26.2.0.0-beta - the mod is rejected
        # with "requires neoforge 26.2 or above" against an actual 26.2.0.0-beta
        # (caught in-game on the 26.2 NeoForge instance). Use the pre-release as the
        # inclusive floor: "[26.2.0.0-beta,)" matches the beta AND every later 26.2
        # build (the eventual stable 26.2.0.0 sorts above its own beta), while still
        # excluding 26.1.x. The minecraft "[26.2]" constraint remains the real gate.
        26.2*)                  NEOFORGE_LV="26.2.0.0-beta" ;;
        *)                      NEOFORGE_LV="20" ;;  # Permissive fallback
    esac

    # Remove other loaders' files
    case $LOADER in
        fabric)
            rm -f "$TEMP_DIR/META-INF/neoforge.mods.toml" 2>/dev/null
            rm -f "$TEMP_DIR/META-INF/mods.toml" 2>/dev/null
            rm -f "$TEMP_DIR/pack.mcmeta" 2>/dev/null
            # Update fabric.mod.json with correct MC version + Java requirement
            if [ -f "$TEMP_DIR/fabric.mod.json" ]; then
                if command -v python3 &> /dev/null; then
                    python3 -c "
import json
try:
    with open('$TEMP_DIR/fabric.mod.json', 'r') as f:
        data = json.load(f)
    data['depends']['minecraft'] = '${MC_VERSION}'
    data['depends']['java'] = '${JAVA_REQ}'
    data['version'] = '${VERSION}'
    with open('$TEMP_DIR/fabric.mod.json', 'w') as f:
        json.dump(data, f, indent=2)
except Exception as e:
    print(f'Warning: Could not update fabric.mod.json: {e}')
"
                else
                    # Fallback: use sed (less reliable but works without Python)
                    sed -i.bak "s/\"minecraft\": \"[^\"]*\"/\"minecraft\": \"${MC_VERSION}\"/" "$TEMP_DIR/fabric.mod.json" 2>/dev/null || true
                    sed -i.bak "s/\"java\": \"[^\"]*\"/\"java\": \"${JAVA_REQ}\"/" "$TEMP_DIR/fabric.mod.json" 2>/dev/null || true
                    rm -f "$TEMP_DIR/fabric.mod.json.bak" 2>/dev/null
                fi
            fi
            ;;
        forge)
            rm -f "$TEMP_DIR/fabric.mod.json" 2>/dev/null
            rm -f "$TEMP_DIR/quilt.mod.json" 2>/dev/null
            rm -f "$TEMP_DIR/META-INF/neoforge.mods.toml" 2>/dev/null
            # Create Forge mods.toml
            mkdir -p "$TEMP_DIR/META-INF"
            cat > "$TEMP_DIR/META-INF/mods.toml" << TOML
modLoader = "javafml"
loaderVersion = "[${FORGE_LV},)"
license = "MIT"
issueTrackerURL = "https://github.com/Bownlux/Retromod/issues"

[[mods]]
modId = "retromod"
version = "${VERSION}"
displayName = "Retromod"
description = '''
Retromod helps older Forge mods run on Minecraft ${MC_VERSION}. It updates
bytecode, mixins, and loader metadata, then keeps a backup of the original mod.
'''
authors = "Bownlux"
logoFile = "assets/retromod/icon.png"

[[dependencies.retromod]]
modId = "forge"
mandatory = true
versionRange = "[${FORGE_LV},)"
ordering = "NONE"
side = "BOTH"

[[dependencies.retromod]]
modId = "minecraft"
mandatory = true
versionRange = "[${MC_VERSION}]"
ordering = "NONE"
side = "BOTH"
TOML
            ;;
        neoforge)
            rm -f "$TEMP_DIR/fabric.mod.json" 2>/dev/null
            rm -f "$TEMP_DIR/quilt.mod.json" 2>/dev/null
            rm -f "$TEMP_DIR/META-INF/mods.toml" 2>/dev/null
            # Create NeoForge mods.toml
            mkdir -p "$TEMP_DIR/META-INF"
            # loaderVersion tracks FancyModLoader, not NeoForge or Minecraft.
            # Their version numbers do not move together, so keep this range
            # permissive and enforce the real requirement in the neoforge
            # dependency below.
            cat > "$TEMP_DIR/META-INF/neoforge.mods.toml" << TOML
modLoader = "javafml"
loaderVersion = "[1,)"
license = "MIT"
issueTrackerURL = "https://github.com/Bownlux/Retromod/issues"

[[mods]]
modId = "retromod"
version = "${VERSION}"
displayName = "Retromod"
description = '''
Retromod helps older NeoForge mods run on Minecraft ${MC_VERSION}. It updates
bytecode, mixins, and loader metadata, then keeps a backup of the original mod.
'''
authors = "Bownlux"
logoFile = "assets/retromod/icon.png"

[[dependencies.retromod]]
modId = "neoforge"
type = "required"
versionRange = "[${NEOFORGE_LV},)"
ordering = "NONE"
side = "BOTH"

[[dependencies.retromod]]
modId = "minecraft"
type = "required"
versionRange = "[${MC_VERSION}]"
ordering = "NONE"
side = "BOTH"
TOML
            ;;
    esac

    # Update manifest with version info
    mkdir -p "$TEMP_DIR/META-INF"
    cat > "$TEMP_DIR/META-INF/MANIFEST.MF" << MANIFEST
Manifest-Version: 1.0
Implementation-Title: Retromod
Implementation-Version: ${VERSION}
Retromod-Target-MC: ${MC_VERSION}
Retromod-Loader: ${LOADER}
Automatic-Module-Name: retromod

MANIFEST

    # Repackage JAR using zip (more compatible than jar command)
    cd "$TEMP_DIR"
    zip -qr "$OUTPUT_PATH" . 2>/dev/null || {
        # Fallback to jar command
        jar cfm "$OUTPUT_PATH" META-INF/MANIFEST.MF . 2>/dev/null || {
            echo "ERROR: Failed to create JAR"
            cd "$ORIG_DIR"
            rm -rf "$TEMP_DIR"
            return 1
        }
    }
    cd "$ORIG_DIR"

    # Cleanup
    rm -rf "$TEMP_DIR"

    echo "  ${MC_VERSION}"
    return 0
}

# Build all combinations
FAILED=0

for LOADER in "${LOADERS[@]}"; do
    echo ""
    # Simple capitalization for display
    case $LOADER in
        fabric) LOADER_CAP="Fabric" ;;
        forge) LOADER_CAP="Forge" ;;
        neoforge) LOADER_CAP="NeoForge" ;;
        *) LOADER_CAP="$LOADER" ;;
    esac
    echo "Building ${LOADER_CAP} JARs..."
    for MC_VERSION in "${MC_VERSIONS[@]}"; do
        if ! create_mod_jar "$LOADER" "$MC_VERSION"; then
            FAILED=$((FAILED + 1))
        fi
    done
done

echo ""
echo "[Step 4/4] Copying assets..."
cp assets/icon_512.png dist/ 2>/dev/null || echo "  (icon_512.png not found)"
cp assets/icon_128.png dist/ 2>/dev/null || echo "  (icon_128.png not found)"

# Count output files
FABRIC_COUNT=$(find dist/Fabric -name "*.jar" 2>/dev/null | wc -l | tr -d ' ')
FORGE_COUNT=$(find dist/Forge -name "*.jar" 2>/dev/null | wc -l | tr -d ' ')
NEOFORGE_COUNT=$(find dist/NeoForge -name "*.jar" 2>/dev/null | wc -l | tr -d ' ')
CLI_COUNT=$(find dist/CLI -name "*.jar" 2>/dev/null | wc -l | tr -d ' ')
TOTAL_COUNT=$((FABRIC_COUNT + FORGE_COUNT + NEOFORGE_COUNT + CLI_COUNT))

echo
echo "Build complete"
echo
echo "Output structure:"
echo "  dist/Fabric/     hosts 1.20 through 26.2"
echo "  dist/Forge/      hosts 1.20 through 26.1.2"
echo "  dist/NeoForge/   hosts 1.20.1 through 26.2"
echo "  dist/CLI/        retromod-${VERSION}-cli.jar"
echo ""
echo "Summary:"
echo "  Fabric:   ${FABRIC_COUNT} JARs"
echo "  Forge:    ${FORGE_COUNT} JARs"
echo "  NeoForge: ${NEOFORGE_COUNT} JARs"
echo "  CLI:      ${CLI_COUNT} JAR"
echo
echo "  Total:    ${TOTAL_COUNT} JARs"

if [ $FAILED -gt 0 ]; then
    echo ""
    echo "  ${FAILED} JAR(s) failed to build."
fi

# A successful command can still produce no jar, so verify the output itself.
EXPECTED_MIN=${EXPECTED_MIN:-60}   # 22 Fabric + 22 Forge + 19 NeoForge + 1 CLI = 64; floor leaves slack
RELEASE_OK=1
if [ "$TOTAL_COUNT" -lt "$EXPECTED_MIN" ]; then
    echo ""
    echo "  The build produced ${TOTAL_COUNT} JARs, but at least ${EXPECTED_MIN} were expected."
    echo "  Do not publish this incomplete dist folder."
    RELEASE_OK=0
fi
for pair in "Fabric:${FABRIC_COUNT}" "Forge:${FORGE_COUNT}" "NeoForge:${NEOFORGE_COUNT}" "CLI:${CLI_COUNT}"; do
    name=${pair%%:*}; n=${pair##*:}
    if [ "$n" -eq 0 ]; then
        echo "  ${name} produced no JARs, so that loader is missing."
        RELEASE_OK=0
    fi
done

echo ""
if [ "$RELEASE_OK" -eq 1 ] && [ "$FAILED" -eq 0 ]; then
    echo "  dist looks complete: ${TOTAL_COUNT} JARs with no failures."
else
    echo "  The build is incomplete. Review the errors above."
    exit 1
fi

echo ""
