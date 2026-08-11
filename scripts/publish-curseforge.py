#!/usr/bin/env python3
"""
Publish every dist/ JAR to CurseForge in one shot.

Retromod ships one JAR per loader × MC version (dist/<Loader>/<MC>/retromod-*.jar),
and on CurseForge each must be uploaded as its own file tagged with that exact MC
version + loader. Doing it by hand on CF's site is ~40 minutes; this does it in one
run.

It loops dist/{Fabric,Forge,NeoForge}/<MC>/retromod-*.jar, resolves CurseForge's
*numeric* game-version IDs (the annoying part of the CF upload API), and POSTs each
JAR with the right MC-version + loader tags.

Environment:
  CF_API_TOKEN   CurseForge author upload-API token   (required)
  CF_PROJECT_ID  numeric CurseForge project id          (required)

Usage:
  publish-curseforge.py --version 1.1.0 --release-type release [--changelog-file CHANGELOG.md] [--dist dist] [--dry-run]

ALWAYS run with --dry-run first: it hits only the read-only game-versions endpoint,
validates the token/project, and prints exactly what it WOULD upload - no files sent.

CurseForge version tags lag real MC releases, so a JAR whose MC version CF doesn't
list yet (e.g. a brand-new 26.2) is SKIPPED with a warning rather than failing the run.
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

API = "https://minecraft.curseforge.com"
REPOSITORY_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _get_json(token, path):
    """GET a CF author-API endpoint, erroring clearly on HTTP / non-JSON failures."""
    r = requests.get(API + path, headers={"X-Api-Token": token}, timeout=30)
    if r.status_code != 200:
        sys.exit(f"ERROR: GET {path} failed (HTTP {r.status_code}). Check CF_API_TOKEN. "
                 f"Body: {r.text[:200]}")
    try:
        return r.json()
    except ValueError:
        sys.exit(f"ERROR: GET {path} returned non-JSON (HTTP {r.status_code}). "
                 f"Body: {r.text[:200]}")


def load_game_versions(token):
    """Map CF's Minecraft-version + modloader NAMES to their numeric IDs.

    CF's /api/game/versions lists versions across MANY types (Minecraft, Bukkit,
    addon/plugin versions, Java, environment, …) and the SAME name (e.g. "1.20.1")
    appears under several. Matching by name alone picks a wrong-type id, which the
    upload API rejects with errorCode 1009 "belongs to an invalid dependency" (and
    inflates the count to thousands). So we resolve the *type* ids first via
    /api/game/version-types and only accept versions of the Minecraft + Modloader
    types.
    """
    types = _get_json(token, "/api/game/version-types")
    mc_type_ids = {
        t.get("id") for t in types
        if str(t.get("slug", "")).startswith("minecraft-")
        or str(t.get("name", "")).startswith("Minecraft ")
    }
    loader_type_ids = {
        t.get("id") for t in types
        if str(t.get("slug", "")) == "modloader" or str(t.get("name", "")) == "Modloader"
    }
    # CurseForge added a mandatory "Environment" version group (Client / Server): an upload that
    # omits it is rejected with errorCode 1021 "You must select at least one version from the
    # environment group of versions". Resolve that group's type id here so we can tag every file.
    env_type_ids = {
        t.get("id") for t in types
        if "environment" in str(t.get("slug", "")).lower()
        or str(t.get("name", "")).strip().lower() == "environment"
    }
    if not mc_type_ids:
        sys.exit("ERROR: could not identify CurseForge's Minecraft version types from "
                 "/api/game/version-types - aborting rather than uploading with wrong "
                 "game-version ids. (CF may have changed its type naming.)")

    # A NAME can legitimately appear under several Minecraft version-type ids (CF keeps
    # older/duplicate groupings alive). Collect ALL candidate ids per name so we can pick
    # deterministically and warn on ambiguity, instead of silently letting last-write-win
    # pick a stale id that the upload API then 500s on.
    mc_candidates = {}
    loaders = {}
    env = {}
    for v in _get_json(token, "/api/game/versions"):
        name = (v.get("name") or "").strip()
        vid = v.get("id")
        tid = v.get("gameVersionTypeID")
        if name.lower() in ("fabric", "forge", "neoforge", "quilt") \
                and (not loader_type_ids or tid in loader_type_ids):
            loaders[name.lower()] = vid
        elif name.lower() in ("client", "server") \
                and (not env_type_ids or tid in env_type_ids):
            env[name.lower()] = vid
        elif tid in mc_type_ids and re.fullmatch(r"\d+\.\d+(\.\d+)?", name):  # "1.20.1", "26.2"
            mc_candidates.setdefault(name, []).append(vid)

    # Canonicalize: CF assigns ids monotonically, so the SMALLEST id for a name is the
    # oldest/original (release) entry; newer duplicate ids are the re-typed/deprecated
    # groupings that the upload API is most likely to choke on. Prefer the smallest.
    mc = {}
    for name, ids in mc_candidates.items():
        ids = sorted(set(ids))
        mc[name] = ids[0]
        if len(ids) > 1:
            print(f"  NOTE {name}: CF lists {len(ids)} ids {ids}; using {ids[0]} "
                  f"(smallest = canonical release). Override with CF_FORCE_VERSION_IDS if wrong.")

    # Escape hatch for the case where CF's canonical-id heuristic is wrong for some version:
    # CF_FORCE_VERSION_IDS="1.20.1=1234,1.20.2=5678" pins exact ids.
    forced = os.environ.get("CF_FORCE_VERSION_IDS", "").strip()
    if forced:
        for pair in forced.split(","):
            if "=" in pair:
                k, val = pair.split("=", 1)
                if val.strip().isdigit():
                    mc[k.strip()] = int(val.strip())
                    print(f"  FORCED {k.strip()} -> {val.strip()} (CF_FORCE_VERSION_IDS)")

    if not mc:
        sys.exit("ERROR: resolved 0 Minecraft versions after type-filtering - the CF API "
                 "response shape may have changed (expected 'gameVersionTypeID' per version). "
                 "Aborting.")

    # Environment override / fallback: CF_ENV_IDS="client=1234,server=5678" pins ids. Retromod is
    # a client+server transformer, so both apply; CF requires at least one (errorCode 1021).
    for pair in os.environ.get("CF_ENV_IDS", "").strip().split(","):
        if "=" in pair:
            k, val = pair.split("=", 1)
            if val.strip().isdigit():
                env[k.strip().lower()] = int(val.strip())
                print(f"  FORCED env {k.strip()} -> {val.strip()} (CF_ENV_IDS)")
    if not env:
        print("  WARNING: could not resolve CurseForge's Client/Server environment version ids; "
              "uploads will be rejected with errorCode 1021. Pin them with "
              "CF_ENV_IDS=\"client=<id>,server=<id>\" if CF renamed the group.")
    return mc, loaders, env


def extract_changelog(path):
    """Pull the first `## [...]` section body out of a Keep-a-Changelog file."""
    if not path or not os.path.exists(path):
        return "See https://github.com/Bownlux/Retromod/blob/main/CHANGELOG.md"
    text = open(path, encoding="utf-8").read()
    m = re.search(r"^## .*?\n(.*?)(?=^## |\Z)", text, re.S | re.M)
    return (m.group(1).strip() if m else text.strip()) or "Release."


def upload(project_id, token, jar, game_version_ids, display_name, changelog, release_type):
    meta = {
        "changelog": changelog,
        "changelogType": "markdown",
        "displayName": display_name,
        "gameVersions": game_version_ids,
        "releaseType": release_type,
    }
    base = os.path.basename(jar)
    # CF's upload API 500s transiently under load and also 500s (rather than a clean 400)
    # on a bad gameVersion id. Retry 5xx a few times with backoff; a persistent 500 then
    # points at the payload, so we surface the exact gameVersions sent for diagnosis.
    r = None
    for attempt in range(1, 4):
        with open(jar, "rb") as fh:
            r = requests.post(
                f"{API}/api/projects/{project_id}/upload-file",
                headers={"X-Api-Token": token},
                data={"metadata": json.dumps(meta)},
                files={"file": (base, fh, "application/java-archive")},
                timeout=180,
            )
        if r.status_code < 500:
            break
        if attempt < 3:
            wait = 3 * attempt
            print(f"  ...  {base}  HTTP {r.status_code} (attempt {attempt}/3), retrying in {wait}s")
            time.sleep(wait)
    if r.status_code == 200:
        try:
            print(f"  OK   {base} -> file {r.json().get('id')}")
            return True
        except ValueError:
            # 200 but not the expected {"id": ...} JSON. Almost always means the
            # request never reached the upload handler - e.g. a wrong/non-numeric
            # CF_PROJECT_ID routed to an HTML error or redirect page. Surface the
            # body instead of crashing on r.json().
            print(f"  FAIL {base}  HTTP 200 but unexpected (non-JSON) body - "
                  f"is CF_PROJECT_ID the numeric id? Body: {r.text[:200]!r}")
            return False
    # Include the exact gameVersions sent: a persistent 500 is almost always one of these
    # ids being unacceptable to CF for this file, and the id is the thing to check/override.
    print(f"  FAIL {base}  HTTP {r.status_code} (gameVersions={game_version_ids}): "
          f"{r.text[:300]}")
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True, help="version string, e.g. 1.1.0")
    # Defaults to what the version string says it is, so a snapshot is never listed as finished.
    ap.add_argument("--release-type", default=None, choices=["release", "beta", "alpha"],
                    help="defaults to beta for snapshot/rc versions, release otherwise")
    ap.add_argument("--changelog-file", default="CHANGELOG.md")
    ap.add_argument("--dist", default="dist")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--only-mc", default="",
                    help="comma-separated MC versions to upload (e.g. 1.20.1,1.20.2); "
                         "the rest are skipped. Use to re-push just the files that failed "
                         "a partial run without re-uploading the ones that succeeded.")
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
    only_mc = {v.strip() for v in args.only_mc.split(",") if v.strip()}

    token = os.environ.get("CF_API_TOKEN")
    project = os.environ.get("CF_PROJECT_ID")
    if not token or not project:
        sys.exit("ERROR: set CF_API_TOKEN and CF_PROJECT_ID in the environment.")
    project = project.strip()
    if not project.isdigit():
        sys.exit(
            f"ERROR: CF_PROJECT_ID must be the NUMERIC project id (e.g. 1234567), not the "
            f"slug/name '{project}'. CurseForge's upload API only accepts the number.\n"
            f"       Find it on your project page: CurseForge -> your project -> the "
            f"'About Project' box in the right sidebar shows 'Project ID: <number>'.\n"
            f"       Then set the repo variable CF_PROJECT_ID to that number "
            f"(Settings -> Secrets and variables -> Actions -> Variables)."
        )

    mc_map, loader_map, env_map = load_game_versions(token)
    env_ids = list(env_map.values())
    changelog = extract_changelog(args.changelog_file)
    print(f"CF knows {len(mc_map)} MC versions, loaders: {sorted(loader_map)}, "
          f"environment: {sorted(env_map)}")
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
        if only_mc and mcver not in only_mc:
            continue
        if loader_name not in loader_map:
            print(f"  SKIP {base}: CF has no '{loader_name}' loader tag"); skipped += 1; continue
        if mcver not in mc_map:
            print(f"  SKIP {base}: CF has no '{mcver}' game version yet"); skipped += 1; continue
        # [MC version, loader, Client, Server]: CF requires an environment-group version too.
        gv = [mc_map[mcver], loader_map[loader_name]] + env_ids
        name = f"Retromod {args.version} ({loader_dir} {mcver})"
        if args.dry_run:
            print(f"  DRY  {base}  gameVersions={gv}  \"{name}\""); uploaded += 1
        else:
            ok = upload(project, token, jar, gv, name, changelog, args.release_type)
            uploaded += ok; failed += (not ok)
            time.sleep(2)  # be gentle with CF's rate limit

    print(f"\nSummary: {uploaded} {'planned' if args.dry_run else 'uploaded'}, "
          f"{skipped} skipped, {failed} failed.")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
