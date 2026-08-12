import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts.release_artifacts import (
    EXPECTED_ARTIFACT_COUNT,
    ReleaseArtifactError,
    _expected_artifacts,
    validate_release_artifacts,
)


VERSION = "1.3.0-snapshot.7"
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


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

    def _write_complete_release(self):
        manifest_lines = []
        for artifact in _expected_artifacts(VERSION, self.dist):
            artifact.path.parent.mkdir(parents=True, exist_ok=True)
            content = f"test artifact {artifact.relative_path}\n".encode("utf-8")
            artifact.path.write_bytes(content)
            digest = hashlib.sha256(content).hexdigest()
            manifest_lines.append(f"{digest}  {artifact.relative_path.as_posix()}\n")
        (self.dist / "SHA256SUMS.txt").write_text(
            "".join(sorted(manifest_lines)),
            encoding="utf-8",
        )

    def _validate(self, version=VERSION):
        return validate_release_artifacts(version, self.dist, self.pom)

    def test_accepts_only_the_complete_release_matrix(self):
        artifacts = self._validate()

        self.assertEqual(EXPECTED_ARTIFACT_COUNT, len(artifacts))
        self.assertEqual(23, sum(a.loader_name == "fabric" for a in artifacts))
        self.assertEqual(23, sum(a.loader_name == "forge" for a in artifacts))
        self.assertEqual(22, sum(a.loader_name == "neoforge" for a in artifacts))
        self.assertEqual(1, sum(not a.is_mod for a in artifacts))
        self.assertTrue(all(a.sha256 for a in artifacts))

    def test_rejects_version_that_does_not_match_pom(self):
        with self.assertRaisesRegex(
                ReleaseArtifactError,
                "requested version '1.3.0-snapshot.8' does not match pom.xml version"):
            self._validate("1.3.0-snapshot.8")

    def test_rejects_missing_and_unexpected_retromod_jars(self):
        expected = self.dist / "Fabric/1.20/retromod-1.3.0-snapshot.7+1.20.jar"
        stale = expected.with_name("retromod-1.3.0-snapshot.6+1.20.jar")
        expected.rename(stale)

        with self.assertRaises(ReleaseArtifactError) as raised:
            self._validate()

        message = str(raised.exception)
        self.assertIn("release artifact is missing: Fabric/1.20/", message)
        self.assertIn("unexpected Retromod JAR in release tree: Fabric/1.20/", message)

    def test_rejects_symlinked_artifact(self):
        artifact = self.dist / "Forge/26.2/retromod-1.3.0-snapshot.7+26.2.jar"
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
        artifact = self.dist / "NeoForge/26.2/retromod-1.3.0-snapshot.7+26.2.jar"
        artifact.write_bytes(b"changed after checksums were generated\n")

        with self.assertRaisesRegex(
                ReleaseArtifactError,
                "checksum mismatch for NeoForge/26.2/"):
            self._validate()

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
                        "1.3.0-snapshot.8",
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


if __name__ == "__main__":
    unittest.main()
