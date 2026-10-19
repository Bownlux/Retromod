#!/usr/bin/env python3
"""Assemble and validate Retromod's Forge and NeoForge bootstrap jars.

Forge and NeoForge claim any mods/ jar that declares a mod locator service for their service
layer and never construct a @Mod from it (#90, #102). So each Forge and NeoForge dist jar is
a small bootstrap: the locator classes for that host's SPI generation, one service file, and
the unchanged Retromod runtime jar nested at META-INF/retromod/runtime.jar. The locator
extracts the runtime and hands it to the loader as an ordinary mod.

build-all.sh and build-all.bat both call this script, so the two builders cannot drift.

assemble validates the jar it wrote and deletes it again if any check fails, so a builder
can never keep a half-checked artifact.

Usage:
  loader_bootstrap.py generation <loader> <minecraft>
  loader_bootstrap.py runtime-loader <loader> <minecraft>
  loader_bootstrap.py runtime-toml <loader> <minecraft>
  loader_bootstrap.py assemble --runtime R --boot-jar B --loader L --minecraft M
                               --version V --output O [--self-hash H]
  loader_bootstrap.py validate --jar J --loader L --minecraft M --version V
                               [--runtime-sha256 S] [--self-hash H]
"""

import argparse
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import sys
import tempfile
import zipfile

RUNTIME_ENTRY = "META-INF/retromod/runtime.jar"
BOOT_PROTOCOL = "1"
BOOT_MODULE_NAME = "retromod.boot"
BOOT_PACKAGE = "com/retromod/boot/"
ICON_ENTRY = "assets/retromod/icon.png"
FIXED_TIMESTAMP = (1980, 2, 1, 0, 0, 0)
MAX_RUNTIME_BYTES = 256 * 1024 * 1024

# One locator per SPI shape. The outer jar carries exactly one of these service files.
GENERATIONS = {
    # Forge 46 to 66 and NeoForge 1.20.1 (FML 47.2.2 keeps net.minecraftforge packages).
    "A": (
        "net.minecraftforge.forgespi.locating.IModLocator",
        "com.retromod.boot.forge.ForgeBootLocator",
        "com/retromod/boot/forge/",
    ),
    # NeoForge 20.2 to 20.4 (FML 1 and 2).
    "A-neo": (
        "net.neoforged.neoforgespi.locating.IModLocator",
        "com.retromod.boot.neoforge.LegacyNeoForgeBootLocator",
        "com/retromod/boot/neoforge/",
    ),
    # NeoForge 20.5 to 26.3 (FML 3 to 12).
    "BC": (
        "net.neoforged.neoforgespi.locating.IModFileCandidateLocator",
        "com.retromod.boot.neoforge.NeoForgeBootLocator",
        "com/retromod/boot/neoforge/",
    ),
}

# Every service that makes Forge or NeoForge claim a jar as a service jar instead of a mod.
# The nested runtime must declare none of them, or RetromodForge and RetromodNeoForge never
# construct (#90, #102).
LOADER_SERVICES = frozenset({
    "cpw.mods.modlauncher.api.ITransformationService",
    "net.minecraftforge.forgespi.locating.IModLocator",
    "net.minecraftforge.forgespi.locating.IDependencyLocator",
    "net.minecraftforge.fml.loading.ImmediateWindowProvider",
    "net.neoforged.neoforgespi.locating.IModLocator",
    "net.neoforged.neoforgespi.locating.IDependencyLocator",
    "net.neoforged.neoforgespi.locating.IModFileCandidateLocator",
    "net.neoforged.neoforgespi.locating.IModFileReader",
    "net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper",
    "net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider",
    "net.neoforged.fml.loading.ImmediateWindowProvider",
})

FORGE_TOML = "META-INF/mods.toml"
NEOFORGE_TOML = "META-INF/neoforge.mods.toml"


class BootstrapError(ValueError):
    """Raised when a bootstrap jar cannot be built or does not have the expected layout."""


def _minecraft_key(version):
    return tuple(int(part) if part.isdigit() else 0 for part in version.split("."))


def boot_generation(loader, minecraft):
    """The locator generation for a host, or None for loaders that get no bootstrap."""
    if loader == "forge":
        return "A"
    if loader != "neoforge":
        return None
    key = _minecraft_key(minecraft)
    if key <= (1, 20, 1):
        return "A"
    if key <= (1, 20, 4):
        return "A-neo"
    return "BC"


def runtime_loader(loader, minecraft):
    """The loader shape of the nested runtime jar.

    NeoForge 1.20.1 (FML 47.2.2) is a Forge fork: it reads META-INF/mods.toml and scans for
    net.minecraftforge's @Mod, so it gets the Forge runtime. A NeoForge-shaped runtime would
    never load there.
    """
    if loader == "neoforge" and boot_generation(loader, minecraft) == "A":
        return "forge"
    return loader


def runtime_toml(loader, minecraft):
    """The mods toml the host's FML reads, which the nested runtime has to carry.

    Only FML 3 and newer (NeoForge 1.20.5 on) read META-INF/neoforge.mods.toml. FML 1 and 2
    (NeoForge 1.20.2 to 1.20.4) read META-INF/mods.toml like Forge and NeoForge 1.20.1, and
    skip a jar without it, so a runtime with the newer name never loads there.
    """
    generation = boot_generation(loader, minecraft)
    if generation is None:
        raise BootstrapError(f"{loader} does not use a bootstrap jar")
    return NEOFORGE_TOML if generation == "BC" else FORGE_TOML


def _sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def _manifest_lines(attributes):
    """Manifest text wrapped at 72 bytes per line, as the JAR specification requires."""
    lines = []
    for key, value in attributes:
        line = f"{key}: {value}".encode("utf-8")
        first = True
        while line:
            limit = 72 if first else 71
            chunk, line = line[:limit], line[limit:]
            lines.append((b"" if first else b" ") + chunk)
            first = False
    return b"\r\n".join(lines) + b"\r\n\r\n"


def parse_manifest(data):
    """Main attributes of a manifest, with continuation lines joined."""
    text = data.decode("utf-8").replace("\r\n", "\n").replace("\r", "\n")
    attributes = {}
    current = None
    for line in text.split("\n"):
        if line == "":
            break
        if line.startswith(" ") and current is not None:
            attributes[current] += line[1:]
            continue
        key, separator, value = line.partition(": ")
        if not separator:
            raise BootstrapError(f"malformed manifest line: {line!r}")
        current = key
        attributes[key] = value
    return attributes


def _zip_info(name, compress):
    info = zipfile.ZipInfo(name, FIXED_TIMESTAMP)
    info.compress_type = zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED
    info.external_attr = 0o644 << 16
    return info


def assemble(runtime_path, boot_jar_path, loader, minecraft, version, output_path):
    """Write the outer bootstrap jar for one host and return the nested runtime's SHA-256."""
    generation = boot_generation(loader, minecraft)
    if generation is None:
        raise BootstrapError(f"{loader} does not use a bootstrap jar")
    service, locator_class, generation_package = GENERATIONS[generation]

    runtime_bytes = Path(runtime_path).read_bytes()
    if len(runtime_bytes) > MAX_RUNTIME_BYTES:
        raise BootstrapError("runtime jar is unexpectedly large")
    runtime_sha = _sha256_bytes(runtime_bytes)
    toml_entry = runtime_toml(loader, minecraft)
    with zipfile.ZipFile(io.BytesIO(runtime_bytes)) as runtime:
        names = set(runtime.namelist())
        if toml_entry not in names:
            raise BootstrapError(f"runtime jar has no {toml_entry}")
        toml_bytes = runtime.read(toml_entry)
        icon_bytes = runtime.read(ICON_ENTRY) if ICON_ENTRY in names else None

    boot_classes = []
    with zipfile.ZipFile(boot_jar_path) as boot:
        for name in sorted(boot.namelist()):
            if not name.endswith(".class") or not name.startswith(BOOT_PACKAGE):
                continue
            relative = name[len(BOOT_PACKAGE):]
            shared = "/" not in relative
            if shared or name.startswith(generation_package):
                boot_classes.append((name, boot.read(name)))
    locator_entry = locator_class.replace(".", "/") + ".class"
    if locator_entry not in {name for name, _ in boot_classes}:
        raise BootstrapError(f"boot jar has no {locator_entry}")

    manifest = _manifest_lines([
        ("Manifest-Version", "1.0"),
        ("Implementation-Title", "Retromod"),
        ("Implementation-Version", version),
        ("Retromod-Target-MC", minecraft),
        ("Retromod-Loader", loader),
        ("Retromod-Boot-Generation", generation),
        ("Retromod-Boot-Protocol", BOOT_PROTOCOL),
        ("Retromod-Runtime-Sha256", runtime_sha),
        # Distinct from the runtime's "retromod" so the two modules never share a name.
        ("Automatic-Module-Name", BOOT_MODULE_NAME),
    ])

    output = Path(output_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(prefix="." + output.name + ".", suffix=".tmp",
                                         dir=output.parent)
    os.close(handle)
    try:
        with zipfile.ZipFile(temporary, "w") as jar:
            jar.writestr(_zip_info("META-INF/MANIFEST.MF", True), manifest)
            jar.writestr(_zip_info(f"META-INF/services/{service}", True),
                         (locator_class + "\n").encode("utf-8"))
            for name, data in boot_classes:
                jar.writestr(_zip_info(name, True), data)
            # Stored, not deflated: it is already compressed and is read once per launch.
            jar.writestr(_zip_info(RUNTIME_ENTRY, False), runtime_bytes)
            # Launchers and mod sites read these to name the file. FML never loads them,
            # because it claims this jar as a service jar.
            jar.writestr(_zip_info(toml_entry, True), toml_bytes)
            if icon_bytes is not None:
                jar.writestr(_zip_info(ICON_ENTRY, True), icon_bytes)
        # mkstemp creates an owner-only file. Release jars are copied to servers that often
        # run as another user, so give this one the same mode as the Fabric and CLI jars.
        os.chmod(temporary, 0o644)
        os.replace(temporary, output)
        temporary = None
    finally:
        if temporary is not None:
            Path(temporary).unlink(missing_ok=True)
    return runtime_sha


def _load_self_hash_module():
    path = Path(__file__).with_name("compute-self-hash.py")
    spec = importlib.util.spec_from_file_location("retromod_compute_self_hash", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate(jar_path, loader, minecraft, version, runtime_sha256=None, self_hash=None):
    """Raise BootstrapError unless the jar is a complete bootstrap for this host."""
    generation = boot_generation(loader, minecraft)
    if generation is None:
        raise BootstrapError(f"{loader} does not use a bootstrap jar")
    service, locator_class, generation_package = GENERATIONS[generation]
    errors = []

    def require(condition, message):
        if not condition:
            errors.append(message)

    with zipfile.ZipFile(jar_path) as jar:
        corrupt = jar.testzip()
        if corrupt is not None:
            raise BootstrapError(f"corrupt entry in {jar_path}: {corrupt}")
        names = jar.namelist()
        require(len(names) == len(set(names)), "duplicate entries")
        if "META-INF/MANIFEST.MF" not in names:
            raise BootstrapError(f"manifest is missing from {jar_path}")
        manifest = parse_manifest(jar.read("META-INF/MANIFEST.MF"))
        expected_manifest = {
            "Implementation-Title": "Retromod",
            "Implementation-Version": version,
            "Retromod-Target-MC": minecraft,
            "Retromod-Loader": loader,
            "Retromod-Boot-Generation": generation,
            "Retromod-Boot-Protocol": BOOT_PROTOCOL,
            "Automatic-Module-Name": BOOT_MODULE_NAME,
        }
        for key, value in expected_manifest.items():
            require(manifest.get(key) == value,
                    f"manifest {key} is {manifest.get(key)!r}, expected {value!r}")

        services = [n for n in names
                    if n.startswith("META-INF/services/") and not n.endswith("/")]
        require(services == [f"META-INF/services/{service}"],
                f"expected exactly one service file for {service}, found {services}")
        if f"META-INF/services/{service}" in names:
            declared = jar.read(f"META-INF/services/{service}").decode("utf-8").split()
            require(declared == [locator_class],
                    f"service file names {declared}, expected {locator_class}")
        require(locator_class.replace(".", "/") + ".class" in names,
                f"{locator_class} is missing")
        for name in names:
            if name.endswith("/"):
                continue
            if name.endswith(".class"):
                require(name.startswith(BOOT_PACKAGE), f"class outside the boot package: {name}")
            if name.startswith("com/retromod/") and not name.startswith(BOOT_PACKAGE):
                errors.append(f"runtime entry leaked into the outer jar: {name}")
            if name.startswith(BOOT_PACKAGE):
                relative = name[len(BOOT_PACKAGE):]
                require("/" not in relative or name.startswith(generation_package),
                        f"boot class from another generation: {name}")

        toml_entry = runtime_toml(loader, minecraft)
        require(toml_entry in names, f"{toml_entry} copy is missing")

        if RUNTIME_ENTRY not in names:
            raise BootstrapError("; ".join(errors + [f"{RUNTIME_ENTRY} is missing"]))
        runtime_bytes = jar.read(RUNTIME_ENTRY)
        actual_sha = _sha256_bytes(runtime_bytes)
        require(manifest.get("Retromod-Runtime-Sha256") == actual_sha,
                "Retromod-Runtime-Sha256 does not match the nested runtime")
        if runtime_sha256 is not None:
            require(actual_sha == runtime_sha256.lower(),
                    "nested runtime differs from the validated runtime jar")
        if toml_entry in names:
            with zipfile.ZipFile(io.BytesIO(runtime_bytes)) as runtime:
                require(runtime.read(toml_entry) == jar.read(toml_entry),
                        f"{toml_entry} copy differs from the runtime's")

    runtime_errors, runtime_self_hash = inspect_runtime(runtime_bytes, loader, minecraft,
                                                        version)
    errors.extend(runtime_errors)
    if self_hash is not None:
        require(runtime_self_hash == self_hash.upper(),
                f"nested runtime self-hash {runtime_self_hash} differs from {self_hash}")
    if errors:
        raise BootstrapError(f"{jar_path}: " + "; ".join(errors))
    return runtime_self_hash


def inspect_runtime(runtime_bytes, loader, minecraft, version):
    """Checks the nested runtime and returns (errors, its self-hash)."""
    errors = []
    shape = runtime_loader(loader, minecraft)
    with zipfile.ZipFile(io.BytesIO(runtime_bytes)) as runtime:
        corrupt = runtime.testzip()
        if corrupt is not None:
            return [f"corrupt nested runtime entry: {corrupt}"], None
        names = set(runtime.namelist())
        for name in names:
            if name.startswith("META-INF/services/"):
                service = name[len("META-INF/services/"):]
                if service in LOADER_SERVICES:
                    errors.append(f"nested runtime declares the loader service {service}")
            if name.startswith(BOOT_PACKAGE):
                errors.append(f"boot class inside the nested runtime: {name}")
            if name.startswith("org/objectweb/asm/"):
                errors.append("nested runtime bundles ASM")
                break
        # FML skips a jar without the toml it reads, so the runtime would never construct.
        toml_entry = runtime_toml(loader, minecraft)
        if toml_entry not in names:
            errors.append(f"nested runtime has no {toml_entry}, which this host reads")
        manifest = parse_manifest(runtime.read("META-INF/MANIFEST.MF")) \
            if "META-INF/MANIFEST.MF" in names else {}
        if manifest.get("Implementation-Version") != version:
            errors.append("nested runtime version differs from the bootstrap")
        if manifest.get("Retromod-Target-MC") != minecraft:
            errors.append("nested runtime targets a different Minecraft version")
        if manifest.get("Retromod-Loader") != shape:
            errors.append(f"nested runtime is not the {shape} runtime")
        if manifest.get("Automatic-Module-Name") != "retromod":
            errors.append("nested runtime module name is not retromod")
    handle, temporary = tempfile.mkstemp(suffix=".jar")
    try:
        with os.fdopen(handle, "wb") as stream:
            stream.write(runtime_bytes)
        runtime_self_hash = _load_self_hash_module().compute_self_hash(temporary)
    finally:
        Path(temporary).unlink(missing_ok=True)
    return errors, runtime_self_hash


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("generation", "runtime-loader", "runtime-toml"):
        sub = commands.add_parser(name)
        sub.add_argument("loader")
        sub.add_argument("minecraft")
    assemble_parser = commands.add_parser("assemble")
    validate_parser = commands.add_parser("validate")
    for sub in (assemble_parser, validate_parser):
        sub.add_argument("--loader", required=True, choices=("forge", "neoforge"))
        sub.add_argument("--minecraft", required=True)
        sub.add_argument("--version", required=True)
    assemble_parser.add_argument("--runtime", required=True)
    assemble_parser.add_argument("--boot-jar", required=True)
    assemble_parser.add_argument("--output", required=True)
    assemble_parser.add_argument("--self-hash")
    validate_parser.add_argument("--jar", required=True)
    validate_parser.add_argument("--runtime-sha256")
    validate_parser.add_argument("--self-hash")
    arguments = parser.parse_args(argv)

    try:
        if arguments.command == "generation":
            print(boot_generation(arguments.loader, arguments.minecraft) or "none")
        elif arguments.command == "runtime-loader":
            print(runtime_loader(arguments.loader, arguments.minecraft))
        elif arguments.command == "runtime-toml":
            print(runtime_toml(arguments.loader, arguments.minecraft))
        elif arguments.command == "assemble":
            runtime_sha = assemble(arguments.runtime, arguments.boot_jar, arguments.loader,
                                   arguments.minecraft, arguments.version, arguments.output)
            try:
                validate(arguments.output, arguments.loader, arguments.minecraft,
                         arguments.version, runtime_sha, arguments.self_hash or None)
            except (BootstrapError, OSError, zipfile.BadZipFile, KeyError):
                Path(arguments.output).unlink(missing_ok=True)
                raise
            print(runtime_sha)
        else:
            validate(arguments.jar, arguments.loader, arguments.minecraft, arguments.version,
                     arguments.runtime_sha256, arguments.self_hash or None)
    except (BootstrapError, OSError, zipfile.BadZipFile, KeyError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
