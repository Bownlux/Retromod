import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
import zipfile

from scripts import loader_bootstrap
from scripts.release_artifacts import (
    EXPECTED_ARTIFACT_COUNT,
    ReleaseArtifactError,
    _expected_artifacts,
    generate_release_checksum_manifest,
    validate_release_artifacts,
)


VERSION = "1.4.0-snapshot.2"
# Deliberately not a real release, so a version bump can never make it match.
MISMATCHED_VERSION = "0.0.0-not-the-pom-version"
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
RUNTIME_CLASS = b"\xca\xfe\xba\xbe runtime fixture"


def _load_compute_self_hash():
    path = REPOSITORY_ROOT / "scripts" / "compute-self-hash.py"
    spec = importlib.util.spec_from_file_location("compute_self_hash_under_test", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _runtime_jar_bytes(loader, minecraft, *, runtime_class=RUNTIME_CLASS, extra=None,
                       toml=None):
    """A minimal runtime jar shaped like build-all.sh output for one host."""
    shape = loader_bootstrap.runtime_loader(loader, minecraft) if loader != "fabric" else loader
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as jar:
        jar.writestr("META-INF/MANIFEST.MF", (
            "Manifest-Version: 1.0\n"
            "Implementation-Title: Retromod\n"
            f"Implementation-Version: {VERSION}\n"
            f"Retromod-Target-MC: {minecraft}\n"
            f"Retromod-Loader: {shape}\n"
            "Automatic-Module-Name: retromod\n\n"))
        if shape == "fabric":
            jar.writestr("fabric.mod.json", "{}")
        else:
            jar.writestr(toml or loader_bootstrap.runtime_toml(loader, minecraft),
                         f'modId = "retromod"\nversion = "{VERSION}"\n')
        jar.writestr("META-INF/services/com.retromod.core.VersionShim", "a.B\n")
        jar.writestr("com/retromod/Fixture.class", runtime_class)
        for name, data in (extra or {}).items():
            jar.writestr(name, data)
    return buffer.getvalue()


def _boot_jar(path):
    with zipfile.ZipFile(path, "w") as jar:
        for name in ("com/retromod/boot/BootCore.class",
                     "com/retromod/boot/forge/ForgeBootLocator.class",
                     "com/retromod/boot/neoforge/LegacyNeoForgeBootLocator.class",
                     "com/retromod/boot/neoforge/NeoForgeBootLocator.class"):
            jar.writestr(name, b"boot " + name.encode("utf-8"))
    return path


class ReleaseArtifactValidationTest(unittest.TestCase):

    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.pom = self.root / "pom.xml"
        self.dist = self.root / "dist"
        self._write_pom(VERSION)
        self._write_complete_release()

    def tearDown(self):
        self.temporary_directory.cleanup()

    def _write_pom(self, version):
        self.pom.write_text(
            "<?xml version=\"1.0\"?>\n"
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
            f"  <version>{version}</version>\n"
            "</project>\n",
            encoding="utf-8",
        )

    def _write_artifact(self, artifact, *, runtime_class=RUNTIME_CLASS, bootstrap=True):
        artifact.path.parent.mkdir(parents=True, exist_ok=True)
        loader = artifact.loader_name or "fabric"
        minecraft = artifact.minecraft_version or "26.3"
        runtime = _runtime_jar_bytes(loader, minecraft, runtime_class=runtime_class)
        if loader == "fabric" or not bootstrap:
            artifact.path.write_bytes(runtime)
            return
        runtime_path = self.root / "runtime-staging.jar"
        runtime_path.write_bytes(runtime)
        loader_bootstrap.assemble(runtime_path, self.boot_jar, loader, minecraft, VERSION,
                                  artifact.path)

    def _write_checksums(self):
        manifest_lines = []
        for artifact in _expected_artifacts(VERSION, self.dist):
            digest = hashlib.sha256(artifact.path.read_bytes()).hexdigest()
            manifest_lines.append(f"{digest}  {artifact.relative_path.as_posix()}\n")
        (self.dist / "SHA256SUMS.txt").write_text(
            "".join(sorted(manifest_lines)),
            encoding="utf-8",
        )

    def _write_complete_release(self):
        self.boot_jar = _boot_jar(self.root / "boot.jar")
        for artifact in _expected_artifacts(VERSION, self.dist):
            self._write_artifact(artifact)
        self._write_checksums()

    def _artifact(self, relative):
        for artifact in _expected_artifacts(VERSION, self.dist):
            if artifact.relative_path.as_posix() == relative:
                return artifact
        raise AssertionError(f"no artifact {relative}")

    def _validate(self, version=VERSION):
        return validate_release_artifacts(version, self.dist, self.pom)

    def test_accepts_only_the_complete_release_matrix(self):
        artifacts = self._validate()

        self.assertEqual(EXPECTED_ARTIFACT_COUNT, len(artifacts))
        self.assertEqual(24, sum(a.loader_name == "fabric" for a in artifacts))
        self.assertEqual(24, sum(a.loader_name == "forge" for a in artifacts))
        self.assertEqual(23, sum(a.loader_name == "neoforge" for a in artifacts))
        self.assertEqual(1, sum(not a.is_mod for a in artifacts))
        self.assertTrue(all(a.sha256 for a in artifacts))

    def test_rejects_version_that_does_not_match_pom(self):
        with self.assertRaisesRegex(
                ReleaseArtifactError,
                "requested version '" + MISMATCHED_VERSION
                + "' does not match pom.xml version"):
            self._validate(MISMATCHED_VERSION)

    def test_rejects_missing_and_unexpected_retromod_jars(self):
        expected = self.dist / f"Fabric/1.20/retromod-{VERSION}+1.20.jar"
        stale = expected.with_name("retromod-1.3.0-snapshot.6+1.20.jar")
        expected.rename(stale)

        with self.assertRaises(ReleaseArtifactError) as raised:
            self._validate()

        message = str(raised.exception)
        self.assertIn("release artifact is missing: Fabric/1.20/", message)
        self.assertIn("unexpected Retromod JAR in release tree: Fabric/1.20/", message)

    def test_rejects_symlinked_artifact(self):
        artifact = self.dist / f"Forge/26.2/retromod-{VERSION}+26.2.jar"
        content = artifact.read_bytes()
        artifact.unlink()
        target = self.root / "outside.jar"
        target.write_bytes(content)
        artifact.symlink_to(target)

        with self.assertRaisesRegex(ReleaseArtifactError, "must not be a symlink"):
            self._validate()

    def test_requires_manifest_to_list_exactly_the_matrix(self):
        manifest = self.dist / "SHA256SUMS.txt"
        lines = manifest.read_text(encoding="utf-8").splitlines()
        removed = lines.pop()
        lines.append(f"{'0' * 64}  CLI/retromod-unexpected-cli.jar")
        manifest.write_text("\n".join(lines) + "\n", encoding="utf-8")

        with self.assertRaises(ReleaseArtifactError) as raised:
            self._validate()

        message = str(raised.exception)
        self.assertIn("checksum manifest is missing:", message)
        self.assertIn(removed.split(maxsplit=1)[1], message)
        self.assertIn("checksum manifest has unexpected entries:", message)

    def test_rejects_checksum_mismatch(self):
        artifact = self.dist / f"NeoForge/26.2/retromod-{VERSION}+26.2.jar"
        artifact.write_bytes(b"changed after checksums were generated\n")

        with self.assertRaisesRegex(
                ReleaseArtifactError,
                "checksum mismatch for NeoForge/26.2/"):
            self._validate()

    def test_rejects_forge_and_neoforge_jars_without_the_bootstrap(self):
        for relative in (f"Forge/26.3/retromod-{VERSION}+26.3.jar",
                         f"NeoForge/1.20.1/retromod-{VERSION}+1.20.1.jar"):
            with self.subTest(relative=relative):
                self._write_complete_release()
                self._write_artifact(self._artifact(relative), bootstrap=False)
                self._write_checksums()

                with self.assertRaisesRegex(ReleaseArtifactError,
                                            "is not a valid bootstrap jar"):
                    self._validate()

    def test_rejects_a_stale_nested_runtime(self):
        artifact = self._artifact(f"NeoForge/26.3/retromod-{VERSION}+26.3.jar")
        self._write_artifact(artifact, runtime_class=b"older build")
        self._write_checksums()

        with self.assertRaisesRegex(ReleaseArtifactError,
                                    "different runtime self-hashes"):
            self._validate()

    def test_bootstrap_generations_cover_the_matrix(self):
        self.assertEqual("A", loader_bootstrap.boot_generation("forge", "1.20"))
        self.assertEqual("A", loader_bootstrap.boot_generation("forge", "26.3"))
        self.assertEqual("A", loader_bootstrap.boot_generation("neoforge", "1.20.1"))
        self.assertEqual("A-neo", loader_bootstrap.boot_generation("neoforge", "1.20.2"))
        self.assertEqual("A-neo", loader_bootstrap.boot_generation("neoforge", "1.20.4"))
        self.assertEqual("BC", loader_bootstrap.boot_generation("neoforge", "1.20.5"))
        self.assertEqual("BC", loader_bootstrap.boot_generation("neoforge", "26.3"))
        self.assertIsNone(loader_bootstrap.boot_generation("fabric", "26.3"))
        self.assertEqual("forge", loader_bootstrap.runtime_loader("neoforge", "1.20.1"))
        self.assertEqual("neoforge", loader_bootstrap.runtime_loader("neoforge", "1.20.2"))

    def test_runtime_carries_the_toml_each_host_reads(self):
        forge_toml = "META-INF/mods.toml"
        neoforge_toml = "META-INF/neoforge.mods.toml"
        for loader, minecraft, expected in (
                ("forge", "1.20", forge_toml),
                ("forge", "26.3", forge_toml),
                ("neoforge", "1.20.1", forge_toml),
                # FML 1 and 2 read only META-INF/mods.toml.
                ("neoforge", "1.20.2", forge_toml),
                ("neoforge", "1.20.3", forge_toml),
                ("neoforge", "1.20.4", forge_toml),
                ("neoforge", "1.20.5", neoforge_toml),
                ("neoforge", "26.3", neoforge_toml)):
            with self.subTest(loader=loader, minecraft=minecraft):
                self.assertEqual(expected, loader_bootstrap.runtime_toml(loader, minecraft))

        artifact = self._artifact(f"NeoForge/1.20.4/retromod-{VERSION}+1.20.4.jar")
        with zipfile.ZipFile(artifact.path) as jar:
            outer = set(jar.namelist())
            nested_bytes = jar.read(loader_bootstrap.RUNTIME_ENTRY)
        with zipfile.ZipFile(io.BytesIO(nested_bytes)) as runtime:
            nested = set(runtime.namelist())
            manifest = loader_bootstrap.parse_manifest(runtime.read("META-INF/MANIFEST.MF"))
        self.assertIn(forge_toml, nested, "NeoForge 1.20.4 skips a runtime without mods.toml")
        self.assertNotIn(neoforge_toml, nested)
        self.assertIn(forge_toml, outer)
        self.assertEqual("neoforge", manifest["Retromod-Loader"],
                         "the runtime is still the NeoForge runtime; only its toml name differs")

    def test_assemble_refuses_a_runtime_its_host_would_skip(self):
        runtime = self.root / "neoforge-1.20.4-runtime.jar"
        runtime.write_bytes(_runtime_jar_bytes("neoforge", "1.20.4",
                                               toml="META-INF/neoforge.mods.toml"))
        output = self.root / "out" / "retromod-1.20.4.jar"
        result = loader_bootstrap.main([
            "assemble", "--runtime", os.fspath(runtime), "--boot-jar",
            os.fspath(self.boot_jar), "--loader", "neoforge", "--minecraft", "1.20.4",
            "--version", VERSION, "--output", os.fspath(output)])
        self.assertEqual(1, result, "FML 2 never loads a runtime without META-INF/mods.toml")
        self.assertFalse(output.exists(), "a failed check must not leave the jar behind")

    def test_bootstrap_layout_and_manifest(self):
        artifact = self._artifact(f"NeoForge/26.3/retromod-{VERSION}+26.3.jar")
        with zipfile.ZipFile(artifact.path) as jar:
            names = jar.namelist()
            manifest = loader_bootstrap.parse_manifest(jar.read("META-INF/MANIFEST.MF"))
            runtime = jar.read(loader_bootstrap.RUNTIME_ENTRY)
        self.assertEqual(
            ["META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator"],
            [n for n in names if n.startswith("META-INF/services/")])
        self.assertNotIn("com/retromod/Fixture.class", names)
        self.assertNotIn("com/retromod/boot/forge/ForgeBootLocator.class", names)
        self.assertEqual("BC", manifest["Retromod-Boot-Generation"])
        self.assertEqual("retromod.boot", manifest["Automatic-Module-Name"])
        self.assertEqual(hashlib.sha256(runtime).hexdigest(),
                         manifest["Retromod-Runtime-Sha256"])
        self.assertEqual(_load_compute_self_hash().compute_self_hash(artifact.path),
                         _load_compute_self_hash().compute_self_hash(
                             io.BytesIO(_runtime_jar_bytes("neoforge", "26.3"))),
                         "the self-hash of a bootstrap is its nested runtime's")

    @unittest.skipIf(os.name == "nt", "POSIX permission bits")
    def test_bootstrap_jar_is_world_readable_like_the_other_release_jars(self):
        artifact = self._artifact(f"Forge/1.20.1/retromod-{VERSION}+1.20.1.jar")
        self.assertEqual(0o644, artifact.path.stat().st_mode & 0o777,
                         "a server run by another user must be able to read the bootstrap jar")

    def test_assemble_refuses_a_runtime_that_declares_a_loader_service(self):
        runtime = self.root / "bad-runtime.jar"
        runtime.write_bytes(_runtime_jar_bytes("forge", "1.20.1", extra={
            "META-INF/services/net.minecraftforge.forgespi.locating.IModLocator": "x.Y\n",
        }))
        output = self.root / "out" / "retromod-bad.jar"
        result = loader_bootstrap.main([
            "assemble", "--runtime", os.fspath(runtime), "--boot-jar",
            os.fspath(self.boot_jar), "--loader", "forge", "--minecraft", "1.20.1",
            "--version", VERSION, "--output", os.fspath(output)])
        self.assertEqual(1, result)
        self.assertFalse(output.exists(), "a failed check must not leave the jar behind")

    def test_assemble_refuses_a_self_hash_mismatch(self):
        runtime = self.root / "runtime.jar"
        runtime.write_bytes(_runtime_jar_bytes("neoforge", "26.3"))
        output = self.root / "out" / "retromod.jar"
        result = loader_bootstrap.main([
            "assemble", "--runtime", os.fspath(runtime), "--boot-jar",
            os.fspath(self.boot_jar), "--loader", "neoforge", "--minecraft", "26.3",
            "--version", VERSION, "--output", os.fspath(output), "--self-hash", "0" * 64])
        self.assertEqual(1, result)
        self.assertFalse(output.exists())

    def test_long_manifest_values_wrap_and_parse_back(self):
        value = "f" * 64
        text = loader_bootstrap._manifest_lines([("Retromod-Runtime-Sha256", value)])
        self.assertTrue(all(len(line) <= 72 for line in text.split(b"\r\n")))
        self.assertEqual(value, loader_bootstrap.parse_manifest(text)["Retromod-Runtime-Sha256"])

    def test_checksum_generation_is_atomic_on_validation_failure(self):
        manifest = self.dist / "SHA256SUMS.txt"
        previous = b"previous manifest\n"
        manifest.write_bytes(previous)
        missing = self.dist / f"Fabric/1.20/retromod-{VERSION}+1.20.jar"
        missing.unlink()

        with self.assertRaises(ReleaseArtifactError):
            generate_release_checksum_manifest(VERSION, self.dist, self.pom)

        self.assertEqual(previous, manifest.read_bytes())
        self.assertEqual([], list(self.dist.glob(".SHA256SUMS.*")))

    def test_checksum_generation_replaces_manifest_with_validated_matrix(self):
        (self.dist / "SHA256SUMS.txt").write_text("stale\n", encoding="utf-8")

        count = generate_release_checksum_manifest(VERSION, self.dist, self.pom)

        self.assertEqual(EXPECTED_ARTIFACT_COUNT, count)
        self.assertEqual(EXPECTED_ARTIFACT_COUNT, len(self._validate()))

    def test_publishers_validate_before_reading_tokens_or_calling_apis(self):
        fake_modules = self.root / "fake-modules"
        fake_modules.mkdir()
        (fake_modules / "requests.py").write_text(
            "def get(*args, **kwargs):\n"
            "    raise AssertionError('publisher called the network before validation')\n"
            "def post(*args, **kwargs):\n"
            "    raise AssertionError('publisher called the network before validation')\n",
            encoding="utf-8",
        )
        environment = os.environ.copy()
        environment.update({
            "PYTHONPATH": os.fspath(fake_modules),
            "MODRINTH_TOKEN": "must-not-be-read",
            "MODRINTH_PROJECT_ID": "must-not-be-read",
            "CF_API_TOKEN": "must-not-be-read",
            "CF_PROJECT_ID": "123456",
        })

        for publisher in ("publish-modrinth.py", "publish-curseforge.py"):
            with self.subTest(publisher=publisher):
                result = subprocess.run(
                    [
                        sys.executable,
                        os.fspath(REPOSITORY_ROOT / "scripts" / publisher),
                        "--version",
                        MISMATCHED_VERSION,
                        "--dist",
                        os.fspath(self.root / "does-not-exist"),
                    ],
                    cwd=REPOSITORY_ROOT,
                    env=environment,
                    capture_output=True,
                    text=True,
                    timeout=10,
                    check=False,
                )

                self.assertNotEqual(0, result.returncode)
                self.assertIn("does not match pom.xml version", result.stderr)
                self.assertNotIn("publisher called the network", result.stderr)

    def test_release_builders_fail_closed_and_validate_loader_jars(self):
        unix_builder = (REPOSITORY_ROOT / "build-all.sh").read_text(encoding="utf-8")
        windows_builder = (REPOSITORY_ROOT / "build-all.bat").read_text(encoding="utf-8")

        self.assertIn(
            'if ! find dist -name "retromod-*.jar" -type f -delete; then',
            unix_builder,
        )
        self.assertIn('if ! cp -- "$SHADED_JAR" "$CLI_OUTPUT"; then', unix_builder)
        self.assertIn('RELEASE_TREE_LINK=$(find dist -type l -print -quit)',
                      unix_builder)
        self.assertIn("scripts/generate-release-checksums.py", unix_builder)
        self.assertIn("*[!0-9]*", unix_builder)
        self.assertIn('"$CHECKSUM_COUNT" -ne "$EXPECTED_TOTAL"', unix_builder)
        self.assertIn('source.stat().st_size != output.stat().st_size', unix_builder)
        self.assertIn('corrupt = jar.testzip()', unix_builder)
        self.assertIn('if [ -e "$OUTPUT_PATH" ] || [ -L "$OUTPUT_PATH" ]; then',
                      unix_builder)
        self.assertIn("validate_loader_jar", unix_builder)
        self.assertIn("python3 scripts/loader_bootstrap.py assemble", unix_builder)
        self.assertIn('--self-hash "$COMPUTED_SELF_HASH"', unix_builder)
        self.assertIn('BOOT_JAR="target/retromod-${VERSION}-boot.jar"', unix_builder)
        self.assertIn('name.startswith("org/objectweb/asm/")', unix_builder)
        self.assertIn(
            'present_metadata == expected_metadata_by_loader[loader]',
            unix_builder,
        )
        self.assertIn('set_dependency("java", java_requirement)', unix_builder)
        self.assertIn('"$TEMP_DIR/quilt.mod.json"', unix_builder)
        self.assertIn("python3 scripts/loader_bootstrap.py runtime-toml", unix_builder)
        self.assertIn('cat > "$TEMP_DIR/${RUNTIME_TOML}"', unix_builder)
        self.assertIn("REQUIRED_DEPENDENCY=$'mandatory = true\\ntype = \"required\"'",
                      unix_builder)

        self.assertIn('set "STALE_DELETE_FAILED=1"', windows_builder)
        self.assertIn("getattr(q.lstat(),'st_reparse_tag',0)", windows_builder)
        self.assertIn('set "CLI_OUTPUT=dist\\CLI\\retromod-%VERSION%-cli.jar"',
                      windows_builder)
        self.assertIn("bad=z.testzip()", windows_builder)
        self.assertIn("scripts\\generate-release-checksums.py", windows_builder)
        self.assertIn("crc=z.testzip()", windows_builder)
        self.assertIn('if exist "!OUTPUT_PATH!" del /q "!OUTPUT_PATH!"', windows_builder)
        self.assertIn("call :validate_loader_jar", windows_builder)
        self.assertIn("scripts\\loader_bootstrap.py\" assemble", windows_builder)
        self.assertIn('--self-hash "!COMPUTED_SELF_HASH!"', windows_builder)
        self.assertIn("x.startswith('org/objectweb/asm/')", windows_builder)
        self.assertIn("n.intersection(lm)==expected[l]", windows_builder)
        self.assertIn("'fabric':{'fabric.mod.json','quilt.mod.json'}", windows_builder)
        self.assertIn('"quilt.mod.json" "%MC_VERSION%"', windows_builder)
        self.assertIn('loader_bootstrap.py" runtime-toml', windows_builder)
        self.assertIn('call :write_neoforge_toml "%TEMP_DIR%\\!RUNTIME_TOML:/=\\!"',
                      windows_builder)
        self.assertIn('if "!LEGACY_MANDATORY!"=="1" echo mandatory = true', windows_builder)

    def test_release_builders_require_the_forge_build_each_minecraft_version_has(self):
        unix_builder = (REPOSITORY_ROOT / "build-all.sh").read_text(encoding="utf-8")
        windows_builder = (REPOSITORY_ROOT / "build-all.bat").read_text(encoding="utf-8")
        unix_floors = dict(re.findall(r'(?m)^\s+([0-9.]+)\)\s+FORGE_LV="(\d+)"', unix_builder))
        windows_floors = dict(re.findall(
            r'(?m)^if "%MC_VERSION%"=="([0-9.]+)" set "FORGE_LV=(\d+)"', windows_builder))

        # Minecraft 1.20 only has Forge 46.x (the last is 1.20-46.0.14), so a floor of 47
        # rejected every Forge 1.20 host.
        for floors in (unix_floors, windows_floors):
            self.assertEqual("46", floors.get("1.20"))
            self.assertEqual("47", floors.get("1.20.1"))

    def test_rolling_release_serializes_and_verifies_the_uploaded_asset(self):
        workflow = (
            REPOSITORY_ROOT / ".github" / "workflows" / "release.yml"
        ).read_text(encoding="utf-8")

        self.assertIn("group: rolling-github-release", workflow)
        self.assertIn("cancel-in-progress: false", workflow)
        self.assertIn("id: freshness", workflow)
        self.assertIn('echo "publish=false" >> "$GITHUB_OUTPUT"', workflow)
        self.assertIn("if: steps.freshness.outputs.publish == 'true'", workflow)
        self.assertIn("id: rolling_release", workflow)
        self.assertIn("overwrite_files: true", workflow)
        self.assertIn("fail_on_unmatched_files: true", workflow)
        self.assertIn("steps.rolling_release.outputs.assets", workflow)
        self.assertIn("GitHub's asset digest does not match the staged jar", workflow)
        self.assertIn("sha256sum --check", workflow)
        self.assertIn("git/refs/tags/latest", workflow)

    def test_embedded_loader_jar_validators_reject_bad_archive_contents(self):
        unix_builder = (REPOSITORY_ROOT / "build-all.sh").read_text(encoding="utf-8")
        windows_builder = (REPOSITORY_ROOT / "build-all.bat").read_text(encoding="utf-8")
        unix_match = re.search(
            r"validate_loader_jar\(\) \{\n\s+python3 .*?<<'PY'\n(.*?)\nPY\n\}",
            unix_builder,
            re.DOTALL,
        )
        windows_match = re.search(
            r'(?m)^!PYTHON_CMD! -c "(.*)" "%~1" "%~2" "%~3" "%~4" "%~5" "%~6"$',
            windows_builder,
        )
        self.assertIsNotNone(unix_match)
        self.assertIsNotNone(windows_match)
        validators = (unix_match.group(1), windows_match.group(1))

        fixture = self.root / "loader.jar"

        def manifest(loader):
            return (
                "Manifest-Version: 1.0\n"
                f"Implementation-Version: {VERSION}\n"
                "Retromod-Target-MC: 26.2\n"
                f"Retromod-Loader: {loader}\n\n"
            )

        def toml_metadata(loader):
            return (
            'modLoader = "javafml"\n'
            '[[mods]]\n'
            'modId = "retromod"\n'
            f'version = "{VERSION}"\n'
            '[[dependencies.retromod]]\n'
            f'modId = "{loader}"\n'
            '[[dependencies.retromod]]\n'
            'modId = "minecraft"\n'
            'versionRange = "[26.2]"\n'
            )

        def default_toml(loader):
            return ("META-INF/mods.toml" if loader == "forge"
                    else "META-INF/neoforge.mods.toml")

        def write_toml_fixture(loader, *, bundled_asm=False, quilt_metadata=False,
                               corrupt_entry=False, metadata_path=None):
            metadata_path = metadata_path or default_toml(loader)
            with zipfile.ZipFile(fixture, "w") as jar:
                jar.writestr("META-INF/MANIFEST.MF", manifest(loader))
                jar.writestr(metadata_path, toml_metadata(loader))
                jar.writestr("com/retromod/Fixture.class", b"crc-payload")
                if bundled_asm:
                    jar.writestr("org/objectweb/asm/ClassReader.class", b"not asm")
                if quilt_metadata:
                    jar.writestr("quilt.mod.json", "{}")
            if corrupt_entry:
                archive = bytearray(fixture.read_bytes())
                payload_offset = archive.index(b"crc-payload")
                archive[payload_offset] ^= 0x01
                fixture.write_bytes(archive)

        expected_quilt_entrypoints = {
            "main": "com.retromod.core.Retromod",
            "client": "com.retromod.core.RetromodClient",
            "server": "com.retromod.core.RetromodServer",
            "preLaunch": "com.retromod.core.RetromodPreLaunch",
        }

        def write_fabric_fixture(*, include_quilt=True, quilt_version=VERSION,
                                 quilt_minecraft="=26.2", quilt_java=">=25",
                                 quilt_entrypoints=expected_quilt_entrypoints):
            fabric_metadata = json.dumps({
                "schemaVersion": 1,
                "id": "retromod",
                "version": VERSION,
                "depends": {
                    "minecraft": "26.2",
                    "java": ">=25",
                },
            })
            quilt_loader = {
                "id": "retromod",
                "version": quilt_version,
                "depends": [
                    {"id": "quilt_loader", "versions": ">=0.20.0"},
                    {"id": "minecraft", "versions": quilt_minecraft},
                    {"id": "java", "versions": quilt_java},
                ],
            }
            if quilt_entrypoints is not None:
                quilt_loader["entrypoints"] = quilt_entrypoints
            quilt_metadata = json.dumps({
                "schema_version": 1,
                "quilt_loader": quilt_loader,
            })
            with zipfile.ZipFile(fixture, "w") as jar:
                jar.writestr("META-INF/MANIFEST.MF", manifest("fabric"))
                jar.writestr("fabric.mod.json", fabric_metadata)
                if include_quilt:
                    jar.writestr("quilt.mod.json", quilt_metadata)
                jar.writestr("com/retromod/Fixture.class", b"crc-payload")

        def run_validator(code, loader, host_toml=None):
            if host_toml is None:
                host_toml = "" if loader == "fabric" else default_toml(loader)
            return subprocess.run(
                [
                    sys.executable,
                    "-c",
                    code,
                    os.fspath(fixture),
                    loader,
                    "26.2",
                    VERSION,
                    ">=25",
                    host_toml,
                ],
                capture_output=True,
                text=True,
                check=False,
            )

        for validator in validators:
            with self.subTest(validator="valid Forge"):
                write_toml_fixture("forge")
                self.assertEqual(0, run_validator(validator, "forge").returncode)
            with self.subTest(validator="bundled ASM"):
                write_toml_fixture("forge", bundled_asm=True)
                self.assertNotEqual(0, run_validator(validator, "forge").returncode)
            with self.subTest(validator="Forge rejects Quilt metadata"):
                write_toml_fixture("forge", quilt_metadata=True)
                self.assertNotEqual(0, run_validator(validator, "forge").returncode)
            with self.subTest(validator="NeoForge rejects Quilt metadata"):
                write_toml_fixture("neoforge", quilt_metadata=True)
                self.assertNotEqual(0, run_validator(validator, "neoforge").returncode)
            with self.subTest(validator="NeoForge runtime for a host that reads mods.toml"):
                write_toml_fixture("neoforge", metadata_path="META-INF/mods.toml")
                self.assertEqual(0, run_validator(
                    validator, "neoforge", "META-INF/mods.toml").returncode)
            with self.subTest(validator="NeoForge runtime missing the toml its host reads"):
                write_toml_fixture("neoforge")
                self.assertNotEqual(0, run_validator(
                    validator, "neoforge", "META-INF/mods.toml").returncode)
            with self.subTest(validator="corrupt class entry"):
                write_toml_fixture("forge", corrupt_entry=True)
                self.assertNotEqual(0, run_validator(validator, "forge").returncode)
            with self.subTest(validator="valid Fabric and Quilt metadata"):
                write_fabric_fixture()
                self.assertEqual(0, run_validator(validator, "fabric").returncode)
            with self.subTest(validator="Fabric requires Quilt metadata"):
                write_fabric_fixture(include_quilt=False)
                self.assertNotEqual(0, run_validator(validator, "fabric").returncode)
            with self.subTest(validator="Quilt requires entrypoints"):
                write_fabric_fixture(quilt_entrypoints=None)
                self.assertNotEqual(0, run_validator(validator, "fabric").returncode)
            for entrypoint in expected_quilt_entrypoints:
                missing_entrypoint = dict(expected_quilt_entrypoints)
                missing_entrypoint.pop(entrypoint)
                with self.subTest(validator=f"Quilt missing {entrypoint} entrypoint"):
                    write_fabric_fixture(quilt_entrypoints=missing_entrypoint)
                    self.assertNotEqual(
                        0, run_validator(validator, "fabric").returncode)

                wrong_entrypoint = dict(expected_quilt_entrypoints)
                wrong_entrypoint[entrypoint] = "com.retromod.core.WrongEntrypoint"
                with self.subTest(validator=f"Quilt wrong {entrypoint} entrypoint"):
                    write_fabric_fixture(quilt_entrypoints=wrong_entrypoint)
                    self.assertNotEqual(
                        0, run_validator(validator, "fabric").returncode)
            with self.subTest(validator="Quilt-native entrypoints are rejected"):
                quilt_native_entrypoints = dict(expected_quilt_entrypoints)
                quilt_native_entrypoints.update({
                    "init": "com.retromod.core.Retromod",
                    "client_init": "com.retromod.core.RetromodClient",
                    "server_init": "com.retromod.core.RetromodServer",
                    "pre_launch": "com.retromod.core.RetromodPreLaunch",
                })
                write_fabric_fixture(quilt_entrypoints=quilt_native_entrypoints)
                self.assertNotEqual(0, run_validator(validator, "fabric").returncode)
            for field, arguments in (
                    ("version", {"quilt_version": "wrong"}),
                    ("Minecraft", {"quilt_minecraft": "=26.1"}),
                    ("Java", {"quilt_java": ">=17"})):
                with self.subTest(validator=f"Quilt {field} mismatch"):
                    write_fabric_fixture(**arguments)
                    self.assertNotEqual(0, run_validator(validator, "fabric").returncode)


if __name__ == "__main__":
    unittest.main()
