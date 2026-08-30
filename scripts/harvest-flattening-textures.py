#!/usr/bin/env python3
"""Derive the 1.13 Flattening texture basename renames from vanilla client jars.

Usage:
    python3 scripts/harvest-flattening-textures.py OLD.jar BOUNDARY.jar CURRENT.jar > out.tsv

OLD is the last pre-Flattening client (1.12.2), BOUNDARY the first post-Flattening one
(1.13), and CURRENT the version packs are being converted to.

Two independent methods produce candidates:

  content   A texture whose PNG bytes are unchanged across the boundary proves its own
            rename. This is exact but misses any texture whose art also changed.
  models    The texture a vanilla model names, before and after. This covers redrawn art
            but can mislead when a model reassigns its slots, so content wins a conflict.

Every candidate is then checked against CURRENT: the old basename must be gone and the new
one must exist. A texture renamed a second time after the boundary is reported so it can be
chained by hand rather than silently pointing at a name the game no longer ships.
"""
import collections
import hashlib
import json
import sys
import zipfile

TEXTURES = "assets/minecraft/textures/"
MODELS = "assets/minecraft/models/"


def textures(jar, directories):
    out = {}
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if not name.startswith(TEXTURES) or not name.endswith(".png"):
                continue
            relative = name[len(TEXTURES):]
            if relative.split("/")[0] in directories:
                out[relative] = hashlib.sha256(z.read(name)).hexdigest()
    return out


def texture_names(jar):
    with zipfile.ZipFile(jar) as z:
        return {n[len(TEXTURES):] for n in z.namelist()
                if n.startswith(TEXTURES) and n.endswith(".png")}


def model_textures(jar):
    out = {}
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if not name.startswith(MODELS) or not name.endswith(".json"):
                continue
            try:
                body = json.loads(z.read(name))
            except (ValueError, UnicodeDecodeError):
                continue
            block = body.get("textures")
            if isinstance(block, dict):
                out[name[len(MODELS):]] = {
                    k: v for k, v in block.items() if isinstance(v, str)}
    return out


def by_content(old_jar, new_jar):
    """Renames proven by unchanged PNG bytes across the boundary."""
    old = textures(old_jar, {"blocks", "items"})
    new = textures(new_jar, {"block", "item"})
    new_by_hash = collections.defaultdict(list)
    old_by_hash = collections.defaultdict(list)
    for path, digest in new.items():
        new_by_hash[digest].append(path)
    for path, digest in old.items():
        old_by_hash[digest].append(path)

    found = {}
    for path, digest in old.items():
        candidates = new_by_hash.get(digest, [])
        moved = path.replace("blocks/", "block/", 1).replace("items/", "item/", 1)
        if not candidates or moved in candidates:
            continue
        # One source and one destination, or the pairing is a guess.
        if len(candidates) == 1 and len(old_by_hash[digest]) == 1:
            found[path] = candidates[0]
    return found


def by_models(old_jar, new_jar):
    """Renames read from the texture a vanilla model names, before and after."""
    old = model_textures(old_jar)
    new = model_textures(new_jar)
    votes = collections.defaultdict(collections.Counter)
    for model in set(old) & set(new):
        for slot, before in old[model].items():
            after = new[model].get(slot)
            if not after or before.startswith("#") or after.startswith("#"):
                continue
            before, after = before.split(":")[-1], after.split(":")[-1]
            if before != after:
                votes[before][after] += 1
    return {f"{k}.png": f"{c.most_common(1)[0][0]}.png"
            for k, c in votes.items() if len(c) == 1}


def basename(path):
    return path.rsplit("/", 1)[-1].removesuffix(".png")


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    old_jar, boundary_jar, current_jar = sys.argv[1:4]

    content = by_content(old_jar, boundary_jar)
    models = by_models(old_jar, boundary_jar)
    # Content is exact, so it settles any disagreement with the model reading.
    merged = dict(models)
    merged.update(content)

    current = texture_names(current_jar)
    rows, chained = [], []
    for source, destination in sorted(merged.items()):
        if basename(source) == basename(destination):
            continue                      # the directory move, handled separately
        kind = "block" if source.startswith("blocks/") else (
            "item" if source.startswith("items/") else None)
        if kind is None:
            continue
        new_directory = destination.split("/", 1)[0]
        if f"{new_directory}/{basename(source)}.png" in current:
            continue                      # the old name is still live: not a rename
        if destination not in current:
            chained.append((kind, basename(source), basename(destination)))
            continue                      # renamed again later; chain it by hand
        rows.append((kind, basename(source), basename(destination)))

    for kind, source, destination in rows:
        print(f"{kind}\t{source}\t{destination}")
    for kind, source, destination in chained:
        print(f"# renamed again after the boundary, chain by hand: "
              f"{kind} {source} -> {destination}", file=sys.stderr)
    print(f"# {len(rows)} renames, {len(chained)} needing a chain", file=sys.stderr)


if __name__ == "__main__":
    main()
