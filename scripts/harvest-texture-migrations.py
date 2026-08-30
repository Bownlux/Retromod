#!/usr/bin/env python3
"""Derive texture path migrations at every version boundary from vanilla client jars.

Usage:
    python3 scripts/harvest-texture-migrations.py JAR [JAR ...] > rows.tsv

Pass the client jars in ascending version order. The last one is treated as the target
that packs are being converted to. A jar states its own resource pack format in
version.json; jars from before that file existed need the format given as JAR=FORMAT, so
the number is visible in the command rather than hidden in a table here.

Each adjacent pair is compared on texture content: a PNG whose bytes are unchanged across
the boundary but whose path changed proves its own migration. Content is used rather than
name similarity because a rename and a redraw look identical by name, and adjacent versions
rarely do both at once, which is why this is done pairwise instead of end to end.

A pair is only accepted when it is unambiguous in both directions, one source and one
destination for that content, so shared art such as a flat colour cannot invent a pair.

Every accepted migration is then followed forward through the remaining jars, so a texture
renamed more than once is emitted pointing at the name the target actually ships. Anything
whose destination is gone by the target is dropped and reported rather than emitted.

Output matches src/main/resources/retromod/texture-migrations.tsv:
    source_pack_format_max<TAB>target_pack_format_min<TAB>source<TAB>destination
"""
import collections
import hashlib
import json
import sys
import zipfile

TEXTURES = "assets/minecraft/textures/"


def split_argument(argument):
    """Split JAR=FORMAT, leaving the format unset when the jar declares its own."""
    if "=" in argument:
        path, _, value = argument.rpartition("=")
        return path, int(value)
    return argument, None


def pack_format(jar):
    """The resource pack format the jar declares in its own version.json.

    A client jar is not itself a resource pack, so there is no pack.mcmeta to read. The
    build stamps version.json instead, and pack_version has taken three shapes: a single
    number, then a resource and data pair, then a major and minor for each side.
    """
    with zipfile.ZipFile(jar) as z:
        version = json.loads(z.read("version.json"))
    pack = version["pack_version"]
    if isinstance(pack, int):
        return pack                          # 1.14 era: a single number
    if "resource" in pack:
        return int(pack["resource"])         # 1.17 era: resource and data split apart
    return int(pack["resource_major"])       # 26.x: each side gained a minor


def textures(jar):
    out = {}
    with zipfile.ZipFile(jar) as z:
        for name in z.namelist():
            if name.startswith(TEXTURES) and name.endswith(".png"):
                out[name[len(TEXTURES):]] = hashlib.sha256(z.read(name)).hexdigest()
    return out


def migrations_between(old, new):
    """Paths whose content survived the boundary unchanged but whose name did not."""
    new_by_hash = collections.defaultdict(list)
    old_by_hash = collections.defaultdict(list)
    for path, digest in new.items():
        new_by_hash[digest].append(path)
    for path, digest in old.items():
        old_by_hash[digest].append(path)

    found = {}
    for path, digest in old.items():
        if path in new:
            continue                                  # the name survived, nothing to do
        candidates = new_by_hash.get(digest, [])
        if len(candidates) == 1 and len(old_by_hash[digest]) == 1:
            found[path] = candidates[0]
    return found


def main():
    arguments = [split_argument(a) for a in sys.argv[1:]]
    if len(arguments) < 2:
        sys.exit(__doc__)

    jars = [path for path, _ in arguments]
    formats = [given if given is not None else pack_format(path)
               for path, given in arguments]
    contents = [textures(j) for j in jars]
    for jar, fmt, tex in zip(jars, formats, contents):
        print(f"# {jar}: pack_format {fmt}, {len(tex)} textures", file=sys.stderr)

    target = contents[-1]
    rows, dropped = [], []
    for i in range(len(jars) - 1):
        found = migrations_between(contents[i], contents[i + 1])
        for source, destination in sorted(found.items()):
            # Follow the destination through every later boundary before emitting it.
            final = destination
            for j in range(i + 1, len(jars) - 1):
                final = migrations_between(contents[j], contents[j + 1]).get(final, final)
            if final not in target:
                dropped.append((jars[i], source, final))
                continue
            if source in target:
                continue          # the old name is still live, so this is not a migration
            rows.append((formats[i], formats[i + 1], source, final))

    # One row per source: the earliest boundary that moved it wins.
    seen = {}
    for source_max, target_min, source, destination in rows:
        if source not in seen:
            seen[source] = (source_max, target_min, source, destination)
    for source_max, target_min, source, destination in sorted(
            seen.values(), key=lambda r: (r[0], r[2])):
        print(f"{source_max}\t{target_min}\t{source}\t{destination}")

    print(f"# {len(seen)} migrations", file=sys.stderr)
    for jar, source, final in dropped:
        print(f"# dropped, destination absent from the target: {jar} {source} -> {final}",
              file=sys.stderr)


if __name__ == "__main__":
    main()
