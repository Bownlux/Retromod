#!/usr/bin/env python3
"""
Publish every dist/ JAR to Modrinth in one shot (the Modrinth twin of publish-curseforge.py).

Retromod ships one JAR per loader x MC version (dist/<Loader>/<MC>/retromod-*.jar). On
Modrinth each loader+MC pair becomes its own version, since a Modrinth version carries a
single game_versions+loaders tag set that all its files share, so one jar per version is
the only way to tag each build with its exact MC version + loader. Version numbers are
made unique per pair (e.g. "1.2.0+1.20.1-fabric"); the display name matches CurseForge's
("Retromod 1.2.0 (Fabric 1.20.1)").

Unlike CurseForge, Modrinth takes MC version *strings* directly (no numeric id lookup), so
this just validates each against Modrinth's game-version + loader tag lists and skips a jar
whose MC version Modrinth does not list yet (e.g. a brand-new 26.2), with a warning rather
than a failure. It also reads the project's existing versions and skips any version_number
that already exists, so re-running is safe (idempotent), unlike the CF uploader.

Environment:
  MODRINTH_TOKEN       Modrinth personal access token with version-create scope   (required)
  MODRINTH_PROJECT_ID  Modrinth project id OR slug (both accepted)                 (required)

Usage:
  publish-modrinth.py --version 1.1.0 --release-type release [--changelog-file docs/changelog.md] [--dist dist] [--dry-run]

Each version's changelog on Modrinth is only a link to that release's entry in the short
docs changelog on bownlux.dev. Modrinth counts version changelogs as part of the project
page, so the release notes stay on the docs site instead of being pasted in.

ALWAYS run with --dry-run first: it hits only read-only endpoints (tags + existing versions),
validates the token/project, and prints exactly what it WOULD upload - no files sent.
"""
import argparse
import json
import os
import re
import sys
import time

from release_artifacts import ReleaseArtifactError, validate_release_artifacts

try:
    import requests
except ImportError:
    sys.exit("ERROR: this script needs `requests` (pip install requests).")

API = "https://api.modrinth.com/v2"
USER_AGENT = "Bownlux/Retromod publish-modrinth.py (bownux@gmail.com)"
REPOSITORY_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _headers(token=None):
    h = {"User-Agent": USER_AGENT}
    if token:
        h["Authorization"] = token
    return h


def _get_json(path, token=None):
    r = requests.get(API + path, headers=_headers(token), timeout=30)
    if r.status_code != 200:
        sys.exit(f"ERROR: GET {path} failed (HTTP {r.status_code}). "
                 f"Check MODRINTH_TOKEN / MODRINTH_PROJECT_ID. Body: {r.text[:200]}")
    try:
        return r.json()
    except ValueError:
        sys.exit(f"ERROR: GET {path} returned non-JSON (HTTP {r.status_code}). Body: {r.text[:200]}")


def load_valid_tags():
    """Modrinth's accepted Minecraft game-version strings and loader names."""
    game_versions = {v["version"] for v in _get_json("/tag/game_version")}
    loaders = {l["name"] for l in _get_json("/tag/loader")}
    return game_versions, loaders


def existing_version_numbers(project, token):
    """version_number strings already on the project, so re-runs skip them."""
    versions = _get_json(f"/project/{project}/version", token)
    return {v.get("version_number") for v in versions}


DOCS_CHANGELOG_URL = "https://bownlux.dev/retromod/changelog.html"


def heading_anchor(heading):
    """The id the docs site's Markdown renderer gives a heading, e.g. "132-september-30-2026"."""
    kept = re.sub(r"[^a-z0-9 -]", "", heading.lower())
    return re.sub(r" +", "-", kept.strip())


def find_changelog_heading(version, text):
    """The docs changelog heading for a version, or None.

    A release is "## 1.3.2, September 30, 2026". A snapshot or release candidate is
    "### Snapshot 1, October 1, 2026" inside "## 1.4.0 Snapshot Line" or "## 1.4.0 Release
    Candidates", and every line restarts its numbering, so the search stays inside that section.
    """
    pre = re.fullmatch(r"(.+)-(snapshot|rc)\.(\d+)", version)
    if not pre:
        m = re.search(rf"^## ({re.escape(version)},[^\n]*)$", text, re.M)
        return m.group(1) if m else None
    base, kind, number = pre.groups()
    section_title = "Snapshot Line" if kind == "snapshot" else "Release Candidates"
    label = "Snapshot" if kind == "snapshot" else "Release Candidate"
    section = re.search(rf"^## {re.escape(base)} {section_title}\n(.*?)(?=^## |\Z)", text, re.M | re.S)
    if not section:
        return None
    m = re.search(rf"^### ({label} {number},[^\n]*)$", section.group(1), re.M)
    return m.group(1) if m else None


def changelog_link(version, path):
    """A one-line Modrinth changelog pointing at this version's entry on bownlux.dev.

    The anchor depends on the heading's date, so it is read from the docs changelog. Without a
    matching entry, the link opens the top of the page.
    """
    url = DOCS_CHANGELOG_URL
    if path and os.path.exists(path):
        heading = find_changelog_heading(version, open(path, encoding="utf-8").read())
        if heading:
            url += "#" + heading_anchor(heading)
        else:
            print(f"WARNING: {path} has no entry for {version}; "
                  f"the changelog link opens the top of the page.")
    return f"Changelog: [Retromod {version}]({url})"


def create_version(project, token, jar, version_number, display_name, mcver,
                   loader_name, changelog, release_type):
    data = {
        "name": display_name,
        "version_number": version_number,
        "changelog": changelog,
        "dependencies": [],
        "game_versions": [mcver],
        "version_type": release_type,
        "loaders": [loader_name],
        "featured": False,
        "project_id": project,
        "file_parts": ["file"],
        "primary_file": "file",
    }
    base = os.path.basename(jar)
    with open(jar, "rb") as fh:
        r = requests.post(
            f"{API}/version",
            headers=_headers(token),
            data={"data": json.dumps(data)},
            files={"file": (base, fh, "application/java-archive")},
            timeout=180,
        )
    if r.status_code in (200, 201):
        try:
            print(f"  OK   {base} -> version {r.json().get('id')} ({version_number})")
        except ValueError:
            print(f"  OK   {base} ({version_number})")
        return True
    print(f"  FAIL {base}  HTTP {r.status_code}: {r.text[:300]}")
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True, help="version string, e.g. 1.1.0")
    # Defaults to what the version string says it is. A snapshot or release candidate uploaded
    # as "release" tells everyone browsing the page that a development build is finished.
    ap.add_argument("--release-type", default=None, choices=["release", "beta", "alpha"],
                    help="defaults to beta for snapshot/rc versions, release otherwise")
    ap.add_argument("--changelog-file", default="docs/changelog.md",
                    help="docs changelog used to find this version's entry for the link")
    ap.add_argument("--dist", default="dist")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    try:
        artifacts = validate_release_artifacts(
            args.version,
            args.dist,
            os.path.join(REPOSITORY_ROOT, "pom.xml"),
        )
    except ReleaseArtifactError as exc:
        sys.exit(f"ERROR: {exc}")

    if args.release_type is None:
        lowered = args.version.lower()
        if "alpha" in lowered:
            args.release_type = "alpha"
        elif "snapshot" in lowered or "-rc" in lowered or "beta" in lowered:
            args.release_type = "beta"
        else:
            args.release_type = "release"

    token = os.environ.get("MODRINTH_TOKEN")
    project = os.environ.get("MODRINTH_PROJECT_ID")
    if not token or not project:
        sys.exit("ERROR: set MODRINTH_TOKEN and MODRINTH_PROJECT_ID in the environment.")
    project = project.strip()

    game_versions, loaders = load_valid_tags()
    existing = existing_version_numbers(project, token)
    changelog = changelog_link(args.version, args.changelog_file)
    print(f"Modrinth knows {len(game_versions)} MC versions; loaders present: "
          f"{sorted(loaders & {'fabric', 'forge', 'neoforge'})}")
    print(f"Project '{project}' already has {len(existing)} versions.")
    print(f"Changelog for each version: {changelog}")
    print(f"Mode: {'DRY RUN (no uploads)' if args.dry_run else 'LIVE upload'} | "
          f"version={args.version} type={args.release_type}\n")

    uploaded = skipped = failed = 0
    for artifact in artifacts:
        if not artifact.is_mod:
            continue
        loader_dir = artifact.loader_dir
        loader_name = artifact.loader_name
        mcver = artifact.minecraft_version
        jar = os.fspath(artifact.path)
        base = os.path.basename(jar)
        version_number = f"{args.version}+{mcver}-{loader_name}"
        # Modrinth caps version_number at 32 chars. Shorten the loader suffix ONLY when the
        # full name would exceed that (e.g. a long snapshot string + 8-char MC + "-neoforge"),
        # so every name that already fits is left unchanged and re-runs stay idempotent. The
        # loaders=[...] field is what actually tags the version's loader, not this suffix.
        if len(version_number) > 32:
            short = {
                "fabric": "fa",
                "neoforge": "nf",
                "forge": "fg",
            }.get(loader_name, loader_name[:2])
            version_number = f"{args.version}+{mcver}-{short}"
        name = f"Retromod {args.version} ({loader_dir} {mcver})"
        if loader_name not in loaders:
            print(f"  SKIP {base}: Modrinth has no '{loader_name}' loader tag"); skipped += 1; continue
        if mcver not in game_versions:
            print(f"  SKIP {base}: Modrinth has no '{mcver}' game version yet"); skipped += 1; continue
        if version_number in existing:
            print(f"  SKIP {base}: version '{version_number}' already on Modrinth"); skipped += 1; continue
        if args.dry_run:
            print(f"  DRY  {base}  game={mcver} loader={loader_name}  \"{version_number}\""); uploaded += 1
        else:
            ok = create_version(project, token, jar, version_number, name, mcver,
                                loader_name, changelog, args.release_type)
            uploaded += ok; failed += (not ok)
            time.sleep(1)  # be gentle with Modrinth's rate limit

    print(f"\nSummary: {uploaded} {'planned' if args.dry_run else 'uploaded'}, "
          f"{skipped} skipped, {failed} failed.")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
