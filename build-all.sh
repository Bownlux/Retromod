#!/bin/bash
# Retromod multi-version build script
# Copyright (c) 2026 RevivalSMP. MIT License.
#
# Builds Retromod for each supported host:
#   - Fabric (1.20 through 26.2)
#   - Forge (1.20 through 26.2)
#   - NeoForge (1.20.1 through 26.2)
#   - Standalone CLI
# Older mods are translated at runtime and do not need separate host jars.

# Keep building after one target fails so the final report can list every failure.
# set -e

VERSION="1.3.0-snapshot.10"
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

# Python generates metadata and verifies the executable self-hash. Release
# output must not fall back to a partial or stale metadata rewrite.
if ! command -v python3 &> /dev/null; then
    echo "ERROR: Python 3 is required for metadata and self-hash checks."
    exit 1
fi

# Check release/build flags.
SKIP_BUILD=false
REQUIRE_SELF_HASH=false
for arg in "$@"; do
    if [ "$arg" = "--skip-build" ]; then
        SKIP_BUILD=true
    elif [ "$arg" = "--require-self-hash" ]; then
        REQUIRE_SELF_HASH=true
    fi
done

if [ "$SKIP_BUILD" = false ]; then
    # Build the base JAR first
    echo "[Step 1/4] Building base JAR with Maven..."
    # The exec plugin calls this script with --skip-build to create dist/. Skipping it here avoids
    # running the entire 69-jar distribution phase once inside Maven and then again below.
    if ! mvn clean package -DskipTests -Dexec.skip=true; then
        echo "ERROR: Maven failed while building the base JAR."
        exit 1
    fi
else
    echo "[Step 1/4] Skipping Maven build (--skip-build flag)"
fi

# Require the exact versioned shaded JAR. Falling back to an arbitrary old
# target/retromod-*-all.jar can silently package stale code under new names.
SHADED_JAR="target/retromod-${VERSION}-all.jar"

if [ ! -f "$SHADED_JAR" ]; then
    echo "ERROR: Expected shaded JAR not found: $SHADED_JAR"
    echo "Make sure maven-shade-plugin ran successfully."
    echo "Build failed"
    exit 1
fi

echo "  Shaded JAR: $SHADED_JAR (with bundled dependencies)"

POM_VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
if [ "$POM_VERSION" != "$VERSION" ]; then
    echo "ERROR: build-all.sh VERSION ($VERSION) does not match pom.xml ($POM_VERSION)."
    exit 1
fi

# Reject a stale embedded hash before any distribution jars are produced. A
# blank hash is allowed for normal development builds, but release automation
# passes --require-self-hash and refuses it.
HASH_DECL=$(javap -classpath "$SHADED_JAR" -p -constants \
    com.retromod.security.SignatureVerifier 2>/dev/null \
    | sed -n '/EXPECTED_SELF_HASH/p')
if [ -z "$HASH_DECL" ]; then
    echo "ERROR: Could not read EXPECTED_SELF_HASH from $SHADED_JAR."
    exit 1
fi
EMBEDDED_SELF_HASH=$(printf '%s\n' "$HASH_DECL" \
    | sed -n 's/.*EXPECTED_SELF_HASH = "\([0-9A-Fa-f]*\)";.*/\1/p' \
    | tr '[:lower:]' '[:upper:]')
COMPUTED_SELF_HASH=$(python3 scripts/compute-self-hash.py "$SHADED_JAR")
if [ -n "$EMBEDDED_SELF_HASH" ] && [ "$EMBEDDED_SELF_HASH" != "$COMPUTED_SELF_HASH" ]; then
    echo "ERROR: Embedded self-hash does not match the shaded JAR."
    echo "  embedded: $EMBEDDED_SELF_HASH"
    echo "  computed: $COMPUTED_SELF_HASH"
    exit 1
fi
if [ "$REQUIRE_SELF_HASH" = true ] && [ -z "$EMBEDDED_SELF_HASH" ]; then
    echo "ERROR: --require-self-hash was set, but the build has no embedded self-hash."
    exit 1
fi
if [ -z "$EMBEDDED_SELF_HASH" ]; then
    echo "  Self-hash: development build (not embedded)"
else
    echo "  Self-hash: $EMBEDDED_SELF_HASH (verified)"
fi

# The checksum validator rejects linked release trees. Refuse them before cleanup or packaging so
# an existing dist link cannot redirect release writes outside the repository.
if [ -L dist ] || { [ -e dist ] && [ ! -d dist ]; }; then
    echo "ERROR: dist must be a real directory, not a link or file."
    exit 1
fi
if [ -d dist ]; then
    if ! RELEASE_TREE_LINK=$(find dist -type l -print -quit); then
        echo "ERROR: Could not inspect the existing dist tree."
        exit 1
    fi
    if [ -n "$RELEASE_TREE_LINK" ]; then
        echo "ERROR: dist contains a linked path: $RELEASE_TREE_LINK"
        exit 1
    fi
fi

# Remove stale Retromod jars only after every build and integrity preflight has
# passed. A bad version or self-hash must not erase the last usable dist tree.
# User-added notes and other files under dist/ are left alone.
if [ -d dist ]; then
    if ! find dist -name "retromod-*.jar" -type f -delete; then
        echo "ERROR: Could not remove stale Retromod JARs from dist/."
        exit 1
    fi
    if ! REMAINING_STALE_JAR=$(find dist -name "retromod-*.jar" -type f -print -quit); then
        echo "ERROR: Could not verify stale Retromod JAR cleanup under dist/."
        exit 1
    fi
    if [ -n "$REMAINING_STALE_JAR" ]; then
        echo "ERROR: A stale Retromod JAR remains under dist/."
        exit 1
    fi
fi

mkdir -p dist/Fabric
mkdir -p dist/Forge
mkdir -p dist/NeoForge
mkdir -p dist/CLI

echo ""
echo "[Step 2/4] Creating CLI tool..."

# CLI is the shaded JAR directly
CLI_OUTPUT="dist/CLI/retromod-${VERSION}-cli.jar"
if [ -e "$CLI_OUTPUT" ] || [ -L "$CLI_OUTPUT" ]; then
    if ! rm -f -- "$CLI_OUTPUT"; then
        echo "ERROR: Could not remove the stale CLI artifact."
        exit 1
    fi
fi
if ! cp -- "$SHADED_JAR" "$CLI_OUTPUT"; then
    rm -f -- "$CLI_OUTPUT"
    echo "ERROR: Could not create the CLI artifact."
    exit 1
fi
if ! python3 - "$SHADED_JAR" "$CLI_OUTPUT" <<'PY'
import hashlib
from pathlib import Path
import sys
import zipfile

source, output = map(Path, sys.argv[1:])


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.digest()


if source.stat().st_size != output.stat().st_size or digest(source) != digest(output):
    raise SystemExit("CLI artifact differs from the shaded JAR")
with zipfile.ZipFile(output) as jar:
    corrupt = jar.testzip()
    if corrupt is not None:
        raise SystemExit(f"CLI artifact has a corrupt entry: {corrupt}")
PY
then
    rm -f -- "$CLI_OUTPUT"
    echo "ERROR: CLI artifact validation failed."
    exit 1
fi
echo "  $CLI_OUTPUT"

echo ""
echo "[Step 3/4] Creating loader-specific JARs..."

# Defensive guard: host MC versions below 1.20 never go into dist/.
# The release build requires JDK 25, while shipped Retromod bytecode targets
# Java 17. Each host jar declares the JVM floor required by that Minecraft
# version. Earlier MC versions are only relevant as TRANSLATION SOURCES (the shim chain walks an
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
# This is more nuanced than a simple "loader started at version X" cutoff because
# loader coverage does not always begin at the first Minecraft patch in a release line.
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
            # NeoForge started at 1.20.1. Current releases cover every listed host after that.
            case $ver in
                # Before NeoForge existed
                1.12*|1.13*|1.14*|1.15*|1.16*|1.17*|1.18*|1.19*|1.20) return 1 ;;
                # Everything else in MC_VERSIONS is supported.
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

# Inspect the packaged archive, not only the staging tree. A failed strip, metadata write, or
# stale-output cleanup must never produce an artifact that still passes count and checksum gates.
validate_loader_jar() {
    python3 - "$1" "$2" "$3" "$4" "$5" <<'PY'
import json
import re
import sys
import zipfile

jar_path, loader, minecraft_version, retromod_version, java_requirement = sys.argv[1:]


def require(condition, message):
    if not condition:
        raise SystemExit(message)


primary_metadata_by_loader = {
    "fabric": "fabric.mod.json",
    "forge": "META-INF/mods.toml",
    "neoforge": "META-INF/neoforge.mods.toml",
}
expected_metadata_by_loader = {
    "fabric": {"fabric.mod.json", "quilt.mod.json"},
    "forge": {"META-INF/mods.toml"},
    "neoforge": {"META-INF/neoforge.mods.toml"},
}

with zipfile.ZipFile(jar_path) as jar:
    corrupt = jar.testzip()
    require(
        corrupt is None,
        f"corrupt entry in {jar_path}: {corrupt}",
    )
    names = set(jar.namelist())
    expected_metadata = primary_metadata_by_loader[loader]
    loader_metadata = set(primary_metadata_by_loader.values()) | {"quilt.mod.json"}
    present_metadata = names & loader_metadata
    require(
        present_metadata == expected_metadata_by_loader[loader],
        f"wrong loader metadata in {jar_path}: {sorted(present_metadata)}",
    )
    require(
        not any(name.startswith("org/objectweb/asm/") for name in names),
        f"bundled ASM remains in {jar_path}",
    )
    if loader != "fabric":
        require(
            not any(name.startswith("javax/annotation/") for name in names),
            f"bundled javax.annotation remains in {jar_path}",
        )

    require("META-INF/MANIFEST.MF" in names, f"manifest is missing from {jar_path}")
    manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8", errors="strict")
    require(
        re.search(r"(?m)^Implementation-Version: "
                  + re.escape(retromod_version) + r"\r?$", manifest) is not None,
        f"manifest version mismatch in {jar_path}",
    )
    require(
        re.search(r"(?m)^Retromod-Target-MC: "
                  + re.escape(minecraft_version) + r"\r?$", manifest) is not None,
        f"manifest Minecraft target mismatch in {jar_path}",
    )
    require(
        re.search(r"(?m)^Retromod-Loader: "
                  + re.escape(loader) + r"\r?$", manifest) is not None,
        f"manifest loader mismatch in {jar_path}",
    )

    metadata = jar.read(expected_metadata).decode("utf-8", errors="strict")
    if loader == "fabric":
        parsed = json.loads(metadata)
        depends = parsed.get("depends")
        require(parsed.get("version") == retromod_version,
                f"Fabric version mismatch in {jar_path}")
        require(isinstance(depends, dict), f"Fabric depends object is missing in {jar_path}")
        require(depends.get("minecraft") == minecraft_version,
                f"Fabric Minecraft target mismatch in {jar_path}")
        require(depends.get("java") == java_requirement,
                f"Fabric Java requirement mismatch in {jar_path}")

        quilt = json.loads(jar.read("quilt.mod.json").decode("utf-8", errors="strict"))
        quilt_loader = quilt.get("quilt_loader")
        require(isinstance(quilt_loader, dict),
                f"Quilt loader object is missing in {jar_path}")
        quilt_depends = quilt_loader.get("depends")
        require(isinstance(quilt_depends, list),
                f"Quilt depends list is missing in {jar_path}")
        quilt_dependencies = {
            dependency.get("id"): dependency.get("versions")
            for dependency in quilt_depends
            if isinstance(dependency, dict)
        }
        expected_quilt_entrypoints = {
            "main": "com.retromod.core.Retromod",
            "client": "com.retromod.core.RetromodClient",
            "server": "com.retromod.core.RetromodServer",
            "preLaunch": "com.retromod.core.RetromodPreLaunch",
        }
        require(quilt_loader.get("version") == retromod_version,
                f"Quilt version mismatch in {jar_path}")
        require(quilt_dependencies.get("minecraft") == "=" + minecraft_version,
                f"Quilt Minecraft target mismatch in {jar_path}")
        require(quilt_dependencies.get("java") == java_requirement,
                f"Quilt Java requirement mismatch in {jar_path}")
        require(quilt_loader.get("entrypoints") == expected_quilt_entrypoints,
                f"Quilt entrypoints mismatch in {jar_path}")
    else:
        require(
            re.search(r'(?m)^version\s*=\s*"'
                      + re.escape(retromod_version) + r'"\s*$', metadata) is not None,
            f"mod version mismatch in {jar_path}",
        )
        require(
            re.search(r'(?m)^versionRange\s*=\s*"\['
                      + re.escape(minecraft_version) + r'\]"\s*$', metadata) is not None,
            f"Minecraft dependency mismatch in {jar_path}",
        )
        require(
            re.search(r'(?m)^modId\s*=\s*"' + re.escape(loader) + r'"\s*$', metadata)
            is not None,
            f"loader dependency mismatch in {jar_path}",
        )
PY
}

remove_staged_path() {
    local path=$1
    if [ -e "$path" ] || [ -L "$path" ]; then
        rm -rf -- "$path" || return 1
    fi
    [ ! -e "$path" ] && [ ! -L "$path" ]
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
    if ! mkdir -p "${ORIG_DIR}/dist/${LOADER_DIR}/${MC_VERSION}"; then
        echo "ERROR: Could not create the output directory for ${LOADER} ${MC_VERSION}."
        return 1
    fi

    # Create temp directory
    local TEMP_DIR
    if ! TEMP_DIR=$(mktemp -d); then
        echo "ERROR: Could not create a temporary build directory."
        return 1
    fi

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
    if ! remove_staged_path "$TEMP_DIR/org/objectweb/asm"; then
        echo "ERROR: Could not strip bundled ASM for ${LOADER} ${MC_VERSION}."
        rm -rf "$TEMP_DIR"
        return 1
    fi

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
        if ! remove_staged_path "$TEMP_DIR/javax/annotation"; then
            echo "ERROR: Could not strip javax.annotation for ${LOADER} ${MC_VERSION}."
            rm -rf "$TEMP_DIR"
            return 1
        fi
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
        # Keep the earliest compatible 26.2 beta as the floor. Maven ordering places every
        # stable 26.2 build above it, while the exact Minecraft dependency remains the host gate.
        26.2*)                  NEOFORGE_LV="26.2.0.0-beta" ;;
        *)                      NEOFORGE_LV="20" ;;  # Permissive fallback
    esac

    # Remove other loaders' files
    case $LOADER in
        fabric)
            for stale_path in \
                    "$TEMP_DIR/META-INF/neoforge.mods.toml" \
                    "$TEMP_DIR/META-INF/mods.toml" \
                    "$TEMP_DIR/pack.mcmeta"; do
                if ! remove_staged_path "$stale_path"; then
                    echo "ERROR: Could not remove non-Fabric metadata for ${MC_VERSION}."
                    rm -rf "$TEMP_DIR"
                    return 1
                fi
            done
            # Keep the shared Fabric/Quilt artifact exact for both loaders.
            if [ ! -f "$TEMP_DIR/fabric.mod.json" ] || [ ! -f "$TEMP_DIR/quilt.mod.json" ]; then
                echo "ERROR: Fabric or Quilt metadata is missing from the shaded JAR."
                rm -rf "$TEMP_DIR"
                return 1
            fi
            if ! python3 - "$TEMP_DIR/fabric.mod.json" "$TEMP_DIR/quilt.mod.json" \
                    "$MC_VERSION" "$JAVA_REQ" "$VERSION" <<'PY'
import json
from pathlib import Path
import sys

fabric_path, quilt_path = map(Path, sys.argv[1:3])
minecraft_version, java_requirement, retromod_version = sys.argv[3:]

data = json.loads(fabric_path.read_text(encoding="utf-8"))
depends = data.get("depends")
if not isinstance(depends, dict):
    raise ValueError("fabric.mod.json has no depends object")
depends["minecraft"] = minecraft_version
depends["java"] = java_requirement
data["version"] = retromod_version
fabric_path.write_text(json.dumps(data, indent=2), encoding="utf-8")

quilt = json.loads(quilt_path.read_text(encoding="utf-8"))
quilt_loader = quilt.get("quilt_loader")
if not isinstance(quilt_loader, dict):
    raise ValueError("quilt.mod.json has no quilt_loader object")
quilt_depends = quilt_loader.get("depends")
if not isinstance(quilt_depends, list):
    raise ValueError("quilt.mod.json has no depends list")


def set_dependency(identifier, version):
    for dependency in quilt_depends:
        if isinstance(dependency, dict) and dependency.get("id") == identifier:
            dependency["versions"] = version
            return
    quilt_depends.append({"id": identifier, "versions": version})


set_dependency("minecraft", "=" + minecraft_version)
set_dependency("java", java_requirement)
quilt_loader["version"] = retromod_version
quilt_path.write_text(json.dumps(quilt, indent=2), encoding="utf-8")
PY
            then
                echo "ERROR: Could not update Fabric and Quilt metadata for ${MC_VERSION}."
                rm -rf "$TEMP_DIR"
                return 1
            fi
            ;;
        forge)
            for stale_path in \
                    "$TEMP_DIR/fabric.mod.json" \
                    "$TEMP_DIR/quilt.mod.json" \
                    "$TEMP_DIR/META-INF/neoforge.mods.toml"; do
                if ! remove_staged_path "$stale_path"; then
                    echo "ERROR: Could not remove non-Forge metadata for ${MC_VERSION}."
                    rm -rf "$TEMP_DIR"
                    return 1
                fi
            done
            # Create Forge mods.toml
            if ! mkdir -p "$TEMP_DIR/META-INF"; then
                echo "ERROR: Could not prepare Forge metadata for ${MC_VERSION}."
                rm -rf "$TEMP_DIR"
                return 1
            fi
            if ! cat > "$TEMP_DIR/META-INF/mods.toml" << TOML
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
            then
                echo "ERROR: Could not write Forge metadata for ${MC_VERSION}."
                rm -rf "$TEMP_DIR"
                return 1
            fi
            ;;
        neoforge)
            for stale_path in \
                    "$TEMP_DIR/fabric.mod.json" \
                    "$TEMP_DIR/quilt.mod.json" \
                    "$TEMP_DIR/META-INF/mods.toml"; do
                if ! remove_staged_path "$stale_path"; then
                    echo "ERROR: Could not remove non-NeoForge metadata for ${MC_VERSION}."
                    rm -rf "$TEMP_DIR"
                    return 1
                fi
            done
            # Create NeoForge mods.toml
            if ! mkdir -p "$TEMP_DIR/META-INF"; then
                echo "ERROR: Could not prepare NeoForge metadata for ${MC_VERSION}."
                rm -rf "$TEMP_DIR"
                return 1
            fi
            # loaderVersion tracks FancyModLoader, not NeoForge or Minecraft.
            # Their version numbers do not move together, so keep this range
            # permissive and enforce the real requirement in the neoforge
            # dependency below.
            if ! cat > "$TEMP_DIR/META-INF/neoforge.mods.toml" << TOML
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
            then
                echo "ERROR: Could not write NeoForge metadata for ${MC_VERSION}."
                rm -rf "$TEMP_DIR"
                return 1
            fi
            ;;
    esac

    # Update manifest with version info
    if ! mkdir -p "$TEMP_DIR/META-INF"; then
        echo "ERROR: Could not prepare the manifest for ${LOADER} ${MC_VERSION}."
        rm -rf "$TEMP_DIR"
        return 1
    fi
    if ! cat > "$TEMP_DIR/META-INF/MANIFEST.MF" << MANIFEST
Manifest-Version: 1.0
Implementation-Title: Retromod
Implementation-Version: ${VERSION}
Retromod-Target-MC: ${MC_VERSION}
Retromod-Loader: ${LOADER}
Automatic-Module-Name: retromod

MANIFEST
    then
        echo "ERROR: Could not write the manifest for ${LOADER} ${MC_VERSION}."
        rm -rf "$TEMP_DIR"
        return 1
    fi

    # zip updates an existing archive instead of truncating it. Remove and verify the exact output
    # first so a failed stale-tree cleanup cannot retain entries removed from this release.
    if [ -e "$OUTPUT_PATH" ] || [ -L "$OUTPUT_PATH" ]; then
        if ! rm -f -- "$OUTPUT_PATH"; then
            echo "ERROR: Could not remove stale output ${OUTPUT_PATH}."
            rm -rf "$TEMP_DIR"
            return 1
        fi
    fi
    if [ -e "$OUTPUT_PATH" ] || [ -L "$OUTPUT_PATH" ]; then
        echo "ERROR: Stale output still exists: ${OUTPUT_PATH}."
        rm -rf "$TEMP_DIR"
        return 1
    fi

    # Repackage JAR using zip (more compatible than jar command).
    if ! (cd "$TEMP_DIR" && zip -qr "$OUTPUT_PATH" .); then
        # A failed zip may leave a partial archive. The fallback must also start fresh.
        if ! rm -f -- "$OUTPUT_PATH"; then
            echo "ERROR: Could not remove partial output ${OUTPUT_PATH}."
            rm -rf "$TEMP_DIR"
            return 1
        fi
        if ! (cd "$TEMP_DIR" && jar cfm "$OUTPUT_PATH" META-INF/MANIFEST.MF .); then
            echo "ERROR: Failed to create JAR for ${LOADER} ${MC_VERSION}."
            rm -f -- "$OUTPUT_PATH"
            rm -rf "$TEMP_DIR"
            return 1
        fi
    fi

    if ! validate_loader_jar \
            "$OUTPUT_PATH" "$LOADER" "$MC_VERSION" "$VERSION" "$JAVA_REQ"; then
        echo "ERROR: Packaged JAR validation failed for ${LOADER} ${MC_VERSION}."
        rm -f -- "$OUTPUT_PATH"
        rm -rf "$TEMP_DIR"
        return 1
    fi

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
echo "  dist/Forge/      hosts 1.20 through 26.2"
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

# A successful command can still omit a target, so require the complete release matrix.
EXPECTED_FABRIC=23
EXPECTED_FORGE=23
EXPECTED_NEOFORGE=22
EXPECTED_CLI=1
EXPECTED_TOTAL=69
RELEASE_OK=1
for triple in \
        "Fabric:${FABRIC_COUNT}:${EXPECTED_FABRIC}" \
        "Forge:${FORGE_COUNT}:${EXPECTED_FORGE}" \
        "NeoForge:${NEOFORGE_COUNT}:${EXPECTED_NEOFORGE}" \
        "CLI:${CLI_COUNT}:${EXPECTED_CLI}"; do
    name=${triple%%:*}
    remainder=${triple#*:}
    actual=${remainder%%:*}
    expected=${remainder##*:}
    if [ "$actual" -ne "$expected" ]; then
        echo "  ${name} produced ${actual} JARs, but ${expected} were expected."
        RELEASE_OK=0
    fi
done
if [ "$TOTAL_COUNT" -ne "$EXPECTED_TOTAL" ]; then
    echo "  The build produced ${TOTAL_COUNT} JARs, but ${EXPECTED_TOTAL} were expected."
    RELEASE_OK=0
fi

if [ "$RELEASE_OK" -eq 1 ] && [ "$FAILED" -eq 0 ]; then
    if CHECKSUM_COUNT=$(python3 scripts/generate-release-checksums.py \
            --version "$VERSION" --dist dist); then
        case $CHECKSUM_COUNT in
            ''|*[!0-9]*)
                echo "  The checksum generator returned an invalid entry count."
                RELEASE_OK=0
                ;;
            *)
                if [ "$CHECKSUM_COUNT" -ne "$EXPECTED_TOTAL" ]; then
                    echo "  The checksum manifest has ${CHECKSUM_COUNT} entries, but ${EXPECTED_TOTAL} were expected."
                    RELEASE_OK=0
                else
                    echo "  SHA-256 manifest: dist/SHA256SUMS.txt (${CHECKSUM_COUNT} entries)"
                fi
                ;;
        esac
    else
        echo "  The checksum manifest could not be generated safely."
        RELEASE_OK=0
    fi
fi

echo ""
if [ "$RELEASE_OK" -eq 1 ] && [ "$FAILED" -eq 0 ]; then
    echo "  dist looks complete: ${TOTAL_COUNT} JARs with no failures."
else
    echo "  The build is incomplete. Review the errors above."
    exit 1
fi

echo ""
