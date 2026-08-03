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
  publish-modrinth.py --version 1.1.0 --release-type release [--changelog-file CHANGELOG.md] [--dist dist] [--dry-run]

ALWAYS run with --dry-run first: it hits only read-only endpoints (tags + existing versions),
validates the token/project, and prints exactly what it WOULD upload - no files sent.
"""
import argparse
import glob
import json
import os
import re
import sys
import time

try:
    import requests
except ImportError:
    sys.exit("ERROR: this script needs `requests` (pip install requests).")

API = "https://api.modrinth.com/v2"
USER_AGENT = "Bownlux/Retromod publish-modrinth.py (bownux@gmail.com)"
LOADER_DIRS = [("Fabric", "fabric"), ("Forge", "forge"), ("NeoForge", "neoforge")]


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


def extract_changelog(path):
    """Pull the first `## [...]` section body out of a Keep-a-Changelog file."""
    if not path or not os.path.exists(path):
        return "See https://github.com/Bownlux/Retromod/blob/main/CHANGELOG.md"
    text = open(path, encoding="utf-8").read()
    m = re.search(r"^## .*?\n(.*?)(?=^## |\Z)", text, re.S | re.M)
    return (m.group(1).strip() if m else text.strip()) or "Release."


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
    ap.add_argument("--changelog-file", default="CHANGELOG.md")
    ap.add_argument("--dist", default="dist")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

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
    changelog = extract_changelog(args.changelog_file)
    print(f"Modrinth knows {len(game_versions)} MC versions; loaders present: "
          f"{sorted(loaders & {'fabric', 'forge', 'neoforge'})}")
    print(f"Project '{project}' already has {len(existing)} versions.")
    print(f"Mode: {'DRY RUN (no uploads)' if args.dry_run else 'LIVE upload'} | "
          f"version={args.version} type={args.release_type}\n")

    uploaded = skipped = failed = 0
    for loader_dir, loader_name in LOADER_DIRS:
        for jar in sorted(glob.glob(f"{args.dist}/{loader_dir}/*/retromod-*.jar")):
            mcver = os.path.basename(os.path.dirname(jar))
            base = os.path.basename(jar)
            version_number = f"{args.version}+{mcver}-{loader_name}"
            # Modrinth caps version_number at 32 chars. Shorten the loader suffix ONLY when the
            # full name would exceed that (e.g. a long snapshot string + 8-char MC + "-neoforge"),
            # so every name that already fits is left unchanged and re-runs stay idempotent. The
            # loaders=[...] field is what actually tags the version's loader, not this suffix.
            if len(version_number) > 32:
                short = {"fabric": "fa", "neoforge": "nf", "forge": "fg"}.get(loader_name, loader_name[:2])
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
