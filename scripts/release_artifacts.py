#!/usr/bin/env python3
"""Validate the complete Retromod release artifact set before publication."""

from dataclasses import dataclass
import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import tempfile
import xml.etree.ElementTree as ET


MINECRAFT_VERSIONS = (
    "1.20",
    "1.20.1",
    "1.20.2",
    "1.20.3",
    "1.20.4",
    "1.20.5",
    "1.20.6",
    "1.21",
    "1.21.1",
    "1.21.2",
    "1.21.3",
    "1.21.4",
    "1.21.5",
    "1.21.6",
    "1.21.7",
    "1.21.8",
    "1.21.9",
    "1.21.10",
    "1.21.11",
    "26.1",
    "26.1.1",
    "26.1.2",
    "26.2",
)

LOADER_MATRIX = (
    ("Fabric", "fabric", MINECRAFT_VERSIONS),
    ("Forge", "forge", MINECRAFT_VERSIONS),
    ("NeoForge", "neoforge", MINECRAFT_VERSIONS[1:]),
)

EXPECTED_MOD_COUNT = 68
EXPECTED_ARTIFACT_COUNT = 69
MAX_MANIFEST_BYTES = 1024 * 1024
CHECKSUM_LINE = re.compile(r"^([0-9A-Fa-f]{64})[ \t]+\*?(.+)$")


class ReleaseArtifactError(ValueError):
    """Raised when a release tree is incomplete, stale, or unsafe to publish."""


@dataclass(frozen=True)
class ReleaseArtifact:
    """One validated artifact and the metadata needed by publisher scripts."""

    path: Path
    relative_path: PurePosixPath
    sha256: str
    loader_dir: str = ""
    loader_name: str = ""
    minecraft_version: str = ""

    @property
    def is_mod(self):
        return bool(self.loader_name)


def _read_project_version(pom_path):
    path = Path(pom_path)
    if path.is_symlink():
        raise ReleaseArtifactError(f"pom.xml must not be a symlink: {path}")
    try:
        root = ET.parse(path).getroot()
    except FileNotFoundError as exc:
        raise ReleaseArtifactError(f"pom.xml was not found: {path}") from exc
    except ET.ParseError as exc:
        raise ReleaseArtifactError(f"pom.xml is not valid XML: {path}: {exc}") from exc

    namespace = ""
    if root.tag.startswith("{"):
        namespace = root.tag.split("}", 1)[0] + "}"
    version_node = root.find(f"{namespace}version")
    version = version_node.text.strip() if version_node is not None and version_node.text else ""
    if not version:
        raise ReleaseArtifactError(f"pom.xml has no project version: {path}")
    return version


def _expected_artifacts(version, dist_dir):
    dist = Path(dist_dir)
    artifacts = []
    for loader_dir, loader_name, versions in LOADER_MATRIX:
        for minecraft_version in versions:
            relative = PurePosixPath(
                loader_dir,
                minecraft_version,
                f"retromod-{version}+{minecraft_version}.jar",
            )
            artifacts.append(
                ReleaseArtifact(
                    path=dist.joinpath(*relative.parts),
                    relative_path=relative,
                    sha256="",
                    loader_dir=loader_dir,
                    loader_name=loader_name,
                    minecraft_version=minecraft_version,
                )
            )

    cli_relative = PurePosixPath("CLI", f"retromod-{version}-cli.jar")
    artifacts.append(
        ReleaseArtifact(
            path=dist.joinpath(*cli_relative.parts),
            relative_path=cli_relative,
            sha256="",
        )
    )
    return artifacts


def _release_tree_entries(dist):
    """Yield every entry without following directory symlinks."""
    for root, directories, files in os.walk(dist, followlinks=False):
        root_path = Path(root)
        for name in directories:
            yield root_path / name
        for name in files:
            yield root_path / name


def _sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _read_manifest(manifest_path, expected_paths, errors):
    if manifest_path.is_symlink():
        errors.append(f"checksum manifest must not be a symlink: {manifest_path}")
        return {}
    if not manifest_path.is_file():
        errors.append(f"checksum manifest is missing: {manifest_path}")
        return {}
    if manifest_path.stat().st_size > MAX_MANIFEST_BYTES:
        errors.append(
            f"checksum manifest is unexpectedly large: {manifest_path} "
            f"({manifest_path.stat().st_size} bytes)"
        )
        return {}

    try:
        lines = manifest_path.read_text(encoding="utf-8").splitlines()
    except UnicodeDecodeError as exc:
        errors.append(f"checksum manifest is not UTF-8: {manifest_path}: {exc}")
        return {}

    checksums = {}
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        match = CHECKSUM_LINE.fullmatch(line)
        if not match:
            errors.append(
                f"checksum manifest line {line_number} is malformed; expected "
                "'<64 hex characters>  <artifact path>'"
            )
            continue
        digest, relative_text = match.groups()
        if relative_text in checksums:
            errors.append(
                f"checksum manifest lists '{relative_text}' more than once "
                f"(line {line_number})"
            )
            continue
        checksums[relative_text] = digest.lower()

    manifest_paths = set(checksums)
    missing = sorted(expected_paths - manifest_paths)
    unexpected = sorted(manifest_paths - expected_paths)
    if missing:
        errors.append("checksum manifest is missing: " + ", ".join(missing))
    if unexpected:
        errors.append("checksum manifest has unexpected entries: " + ", ".join(unexpected))
    if len(checksums) != EXPECTED_ARTIFACT_COUNT:
        errors.append(
            f"checksum manifest lists {len(checksums)} unique artifacts; "
            f"expected {EXPECTED_ARTIFACT_COUNT}"
        )
    return checksums


def generate_release_checksum_manifest(version, dist_dir="dist", pom_path="pom.xml"):
    """Atomically write the checksum manifest for the exact release matrix."""
    project_version = _read_project_version(pom_path)
    if version != project_version:
        raise ReleaseArtifactError(
            f"requested version '{version}' does not match pom.xml version "
            f"'{project_version}'; rebuild or pass --version {project_version}"
        )

    dist = Path(dist_dir)
    if dist.is_symlink():
        raise ReleaseArtifactError(f"distribution directory must not be a symlink: {dist}")
    if not dist.is_dir():
        raise ReleaseArtifactError(f"distribution directory was not found: {dist}")

    expected = _expected_artifacts(version, dist)
    expected_paths = {artifact.relative_path.as_posix() for artifact in expected}
    errors = []
    for entry in _release_tree_entries(dist):
        relative = entry.relative_to(dist).as_posix()
        if entry.is_symlink():
            errors.append(f"release tree contains a symlink: {relative}")
        if entry.is_file() and entry.suffix.lower() == ".jar" \
                and relative not in expected_paths:
            errors.append(f"unexpected JAR in release tree: {relative}")

    digests = {}
    for artifact in expected:
        relative = artifact.relative_path.as_posix()
        if artifact.path.is_symlink():
            errors.append(f"release artifact must not be a symlink: {artifact.relative_path}")
        elif not artifact.path.is_file():
            errors.append(f"release artifact is missing: {artifact.relative_path}")
        else:
            digests[relative] = _sha256(artifact.path)

    if errors:
        details = "\n".join(f"  - {error}" for error in dict.fromkeys(errors))
        raise ReleaseArtifactError(
            "release checksums were not generated:\n" + details
        )

    manifest_path = dist / "SHA256SUMS.txt"
    temporary_path = None
    try:
        with tempfile.NamedTemporaryFile(
                mode="w",
                encoding="utf-8",
                newline="\n",
                prefix=".SHA256SUMS.",
                dir=dist,
                delete=False) as temporary:
            temporary_path = Path(temporary.name)
            for relative in sorted(digests):
                temporary.write(f"{digests[relative]}  {relative}\n")
            temporary.flush()
            os.fsync(temporary.fileno())

        staged_errors = []
        staged = _read_manifest(temporary_path, expected_paths, staged_errors)
        for relative, digest in digests.items():
            if staged.get(relative) != digest:
                staged_errors.append(f"staged checksum mismatch for {relative}")
        if staged_errors:
            details = "\n".join(
                f"  - {error}" for error in dict.fromkeys(staged_errors)
            )
            raise ReleaseArtifactError(
                "release checksum staging failed:\n" + details
            )

        os.replace(temporary_path, manifest_path)
        temporary_path = None
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)

    return len(digests)


def validate_release_artifacts(version, dist_dir="dist", pom_path="pom.xml"):
    """Return the exact validated release matrix or raise an actionable error."""
    project_version = _read_project_version(pom_path)
    if version != project_version:
        raise ReleaseArtifactError(
            f"requested version '{version}' does not match pom.xml version "
            f"'{project_version}'; rebuild or pass --version {project_version}"
        )

    dist = Path(dist_dir)
    if dist.is_symlink():
        raise ReleaseArtifactError(f"distribution directory must not be a symlink: {dist}")
    if not dist.is_dir():
        raise ReleaseArtifactError(f"distribution directory was not found: {dist}")

    expected = _expected_artifacts(version, dist)
    if len(expected) != EXPECTED_ARTIFACT_COUNT:
        raise AssertionError("internal release matrix count is wrong")
    expected_paths = {artifact.relative_path.as_posix() for artifact in expected}
    errors = []

    for entry in _release_tree_entries(dist):
        relative = entry.relative_to(dist).as_posix()
        if entry.is_symlink():
            errors.append(f"release tree contains a symlink: {relative}")
        lower_name = entry.name.lower()
        if lower_name.startswith("retromod") and lower_name.endswith(".jar") \
                and relative not in expected_paths:
            errors.append(f"unexpected Retromod JAR in release tree: {relative}")

    for artifact in expected:
        if artifact.path.is_symlink():
            errors.append(f"release artifact must not be a symlink: {artifact.relative_path}")
        elif not artifact.path.is_file():
            errors.append(f"release artifact is missing: {artifact.relative_path}")

    manifest_path = dist / "SHA256SUMS.txt"
    checksums = _read_manifest(manifest_path, expected_paths, errors)

    validated = []
    for artifact in expected:
        relative = artifact.relative_path.as_posix()
        expected_digest = checksums.get(relative)
        if expected_digest is None or artifact.path.is_symlink() or not artifact.path.is_file():
            continue
        actual_digest = _sha256(artifact.path)
        if actual_digest != expected_digest:
            errors.append(
                f"checksum mismatch for {relative}: manifest has {expected_digest}, "
                f"file is {actual_digest}; rebuild dist before publishing"
            )
        validated.append(
            ReleaseArtifact(
                path=artifact.path,
                relative_path=artifact.relative_path,
                sha256=actual_digest,
                loader_dir=artifact.loader_dir,
                loader_name=artifact.loader_name,
                minecraft_version=artifact.minecraft_version,
            )
        )

    if errors:
        unique_errors = list(dict.fromkeys(errors))
        details = "\n".join(f"  - {error}" for error in unique_errors)
        raise ReleaseArtifactError(
            "release artifacts are not safe to publish:\n" + details
        )
    if len(validated) != EXPECTED_ARTIFACT_COUNT:
        raise ReleaseArtifactError(
            f"validated {len(validated)} artifacts; expected {EXPECTED_ARTIFACT_COUNT}"
        )
    if sum(artifact.is_mod for artifact in validated) != EXPECTED_MOD_COUNT:
        raise AssertionError("internal mod artifact count is wrong")
    return tuple(validated)
