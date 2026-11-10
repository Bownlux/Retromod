#!/usr/bin/env python3
"""Build the Mojang-name renames a NeoForge or Forge mod needs on a newer Minecraft host.

Fabric mods name Minecraft members by intermediary id, so the bundled intermediary table moves
them to each host's names. A Mojang-named mod carries the names of the version it was built for.
This script joins every version's Mojang mappings with that version's Fabric intermediary, so one
intermediary id ties a member's name in each version together, and writes a row wherever the name
a mod was built with differs from the host's name for the same id.

Sources, per version from 1.21 to 1.21.11 (in <maps>/<version>/):
  client_mappings.txt, server_mappings.txt  Mojang's official mappings (piston-meta, sha1 checked)
  intermediary.tiny                         Fabric intermediary v2 (maven.fabricmc.net, sha1 checked)
26.x hosts ship no obfuscation, so their names come from the bundled intermediary table, and every
26.x row is kept only when the host jar given for it declares the new name and lacks the old one.

  python3 scripts/harvest-mojang-renames.py --fetch <maps>
  python3 scripts/harvest-mojang-renames.py <maps> --host-jar 26.1=<26.1.2 client.jar> \
      --host-jar 26.2=<26.2 client.jar> --host-jar 26.3=<26.3 client.jar> \
      --source-jar 1.21.1=<1.21.1 merged jar> --source-jar 1.21.11=<1.21.11 merged jar> \
      --out src/main/resources/retromod/mojang-renames.tsv --report <dir>

Rows (tab separated): KIND OWNER NAME DESC NEW_NAME HOST_OWNER HOST_DESC FROM UNTIL HOST_FROM
HOST_UNTIL. OWNER, NAME and DESC are what a mod built for a version in [FROM, UNTIL) carries; FROM
is * when the name already existed in the oldest version read. NEW_NAME is the name on every host
in [HOST_FROM, HOST_UNTIL), with * for no upper bound. A member a host no longer has takes the
newest name it had before that host, so a later shim keyed on that name still applies. HOST_OWNER
and HOST_DESC are = when unchanged. A CLASS row has - for NAME and DESC and the new class in
NEW_NAME. A rename is left out when the old name names another class or member in any version
from the rename to the host, and a class the curated 26.1 move table already moves keeps its move.
With --source-jar (the obfuscated merged jars of 1.21.1 and 1.21.11), a rename is also left out
when its generic signature changed beyond class renames, such as List<Player> becoming
List<ContainerUser> under the same erased descriptor, or when a method turned static or instance.

The report folder gets descriptor-changed.tsv (members whose host descriptor differs beyond class
renames, which a rename cannot fix) and summary.txt.
"""
import argparse
import hashlib
import json
import os
import re
import struct
import sys
import urllib.request
import zipfile
from collections import defaultdict

VERSIONS = ["1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7",
            "1.21.8", "1.21.9", "1.21.10", "1.21.11"]
UNOBFUSCATED = ["26.1", "26.2", "26.3"]
# A renamed class must keep at least this share of its members, or it counts as rebuilt.
KEPT_MEMBERS = 0.5
# Renames that also changed what the member means, which a rename would hide. 1.21.2's max build
# height getters became inclusive; LegacyBuildHeightBridge answers the old ones.
CHANGED_MEANING = {
    ("net/minecraft/world/level/LevelHeightAccessor", "getMaxBuildHeight", "()I"),
    ("net/minecraft/world/level/LevelHeightAccessor", "getMaxSection", "()I"),
}
HOSTS = VERSIONS[2:] + UNOBFUSCATED
ORDER = {v: i for i, v in enumerate(VERSIONS + UNOBFUSCATED)}
PRIMITIVES = {"int": "I", "long": "J", "short": "S", "byte": "B", "char": "C",
              "boolean": "Z", "float": "F", "double": "D", "void": "V"}
CLASS_LINE = re.compile(r"^(\S+) -> (\S+):$")
MEMBER_LINE = re.compile(r"^\s+(?:\d+:\d+:)?(\S+) ([^\s(]+)(?:\(([^)]*)\))?(?::\d+:\d+)? -> (\S+)$")
USER_AGENT = {"User-Agent": "Retromod Mojang rename harvest"}


def fetch(maps):
    """Downloads each version's mappings and intermediary, checking every sha1."""
    def get(url):
        with urllib.request.urlopen(urllib.request.Request(url, headers=USER_AGENT)) as r:
            return r.read()

    def sha1(data):
        return hashlib.sha1(data).hexdigest()

    manifest = json.loads(get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
    entries = {v["id"]: v for v in manifest["versions"]}
    for version in VERSIONS:
        folder = os.path.join(maps, version)
        os.makedirs(folder, exist_ok=True)
        meta_bytes = get(entries[version]["url"])
        if sha1(meta_bytes) != entries[version]["sha1"]:
            sys.exit(f"{version}: version json checksum mismatch")
        meta = json.loads(meta_bytes)
        for side in ("client_mappings", "server_mappings"):
            item = meta["downloads"][side]
            data = get(item["url"])
            if sha1(data) != item["sha1"]:
                sys.exit(f"{version}: {side} checksum mismatch")
            with open(os.path.join(folder, side + ".txt"), "wb") as out:
                out.write(data)
        base = f"https://maven.fabricmc.net/net/fabricmc/intermediary/{version}/intermediary-{version}-v2.jar"
        jar = get(base)
        if sha1(jar) != get(base + ".sha1").decode().strip()[:40]:
            sys.exit(f"{version}: intermediary checksum mismatch")
        with zipfile.ZipFile(__import__("io").BytesIO(jar)) as archive:
            with open(os.path.join(folder, "intermediary.tiny"), "wb") as out:
                out.write(archive.read("mappings/mappings.tiny"))
        print(f"{version}: fetched")


def type_desc(name):
    arrays = ""
    while name.endswith("[]"):
        arrays += "["
        name = name[:-2]
    return arrays + (PRIMITIVES.get(name) or "L" + name.replace(".", "/") + ";")


def map_desc(desc, mapping):
    """Replaces every class in a descriptor through mapping, or returns None when one is unknown."""
    out, i = [], 0
    while i < len(desc):
        c = desc[i]
        if c == "L":
            end = desc.index(";", i)
            name = desc[i + 1:end]
            mapped = mapping(name)
            if mapped is None:
                return None
            out.append("L" + mapped + ";")
            i = end + 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


SIGNATURE_CLASS = re.compile(r"L([^;<>.:]+)")


def map_signature(signature, mapping):
    """Replaces every class in a generic signature through mapping, or returns None when one is unknown."""
    out, position = [], 0
    for match in SIGNATURE_CLASS.finditer(signature):
        mapped = mapping(match.group(1))
        if mapped is None:
            return None
        out.append(signature[position:match.start(1)])
        out.append(mapped)
        position = match.end(1)
    out.append(signature[position:])
    return "".join(out)


def read_version(folder):
    """Returns (classes, members): intermediary class -> Mojang class, and member rows by identity."""
    obf_to_moj = {}
    fields, methods = {}, defaultdict(list)
    current = None
    for side in ("client_mappings.txt", "server_mappings.txt"):
        with open(os.path.join(folder, side), encoding="utf-8") as text:
            for line in text:
                if line.startswith("#") or line.lstrip().startswith("#"):
                    continue
                match = CLASS_LINE.match(line.rstrip("\n"))
                if match:
                    current = match.group(2).replace(".", "/")
                    obf_to_moj[current] = match.group(1).replace(".", "/")
                    continue
                match = MEMBER_LINE.match(line.rstrip("\n"))
                if not match or current is None:
                    continue
                kind, name, args, obf = match.groups()
                # Inlined frames name another class's method with a dotted name; they are not members.
                if "." in name:
                    continue
                if args is None:
                    fields[(current, obf, type_desc(kind))] = name
                else:
                    arg_desc = "".join(type_desc(a.strip()) for a in args.split(",") if a.strip())
                    methods[(current, obf)].append((name, "(" + arg_desc + ")" + type_desc(kind)))
    moj_to_obf = {v: k for k, v in obf_to_moj.items()}

    classes, members, obfuscated = {}, {}, {}
    current_obf = current_int = None
    with open(os.path.join(folder, "intermediary.tiny"), encoding="utf-8") as tiny:
        next(tiny)
        for line in tiny:
            parts = line.rstrip("\n").split("\t")
            if parts[0] == "c":
                current_obf, current_int = parts[1], parts[2]
                if current_obf in obf_to_moj:
                    classes[current_int] = obf_to_moj[current_obf]
                continue
            if len(parts) < 5 or parts[0] != "" or parts[1] not in ("m", "f"):
                continue
            kind, obf_desc, obf_name, int_name = parts[1], parts[2], parts[3], parts[4]
            owner = obf_to_moj.get(current_obf)
            if owner is None:
                continue
            if kind == "f":
                moj_desc = map_desc(obf_desc, lambda n: obf_to_moj.get(n, n))
                name = fields.get((current_obf, obf_name, moj_desc))
                if name is not None:
                    members[(current_int, "f", int_name)] = (owner, name, moj_desc)
                    obfuscated[(owner, "f", name, moj_desc)] = (current_obf, obf_name, obf_desc)
            else:
                want = map_desc(obf_desc, lambda n: obf_to_moj.get(n, n))
                for name, desc in methods.get((current_obf, obf_name), ()):
                    if desc == want:
                        members[(current_int, "m", int_name)] = (owner, name, desc)
                        obfuscated[(owner, "m", name, desc)] = (current_obf, obf_name, obf_desc)
                        break
    return classes, members, (obf_to_moj, obfuscated)


def read_tsv_table(path):
    """Reads the bundled intermediary table: id -> 26.x name, per kind."""
    table = {"CLASS": {}, "METHOD": {}, "FIELD": {}}
    with open(path, encoding="utf-8") as text:
        for line in text:
            if line.startswith("#") or not line.strip():
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3 and parts[0] in table:
                table[parts[0]].setdefault(parts[1], parts[2])
    return table


def read_moves(path):
    moves = {}
    with open(path, encoding="utf-8") as text:
        for line in text:
            if line.startswith("#") or not line.strip():
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 2:
                moves[parts[0]] = parts[1]
    return moves


def parse_class(data, signatures=None):
    """Returns (name, super, interfaces, fields, methods) with members as (name, desc) pairs.

    With a dict for signatures, fills it with each generic Signature attribute: the class's under
    None, and each member's under (kind, name, desc).
    """
    count = struct.unpack(">H", data[8:10])[0]
    pool, i, index = {}, 10, 1
    while index < count:
        tag = data[i]
        if tag == 1:
            length = struct.unpack(">H", data[i + 1:i + 3])[0]
            pool[index] = data[i + 3:i + 3 + length].decode("utf-8", "replace")
            i += 3 + length
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = struct.unpack(">H", data[i + 1:i + 3])[0]
            i += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            i += 5
        elif tag in (5, 6):
            i += 9
            index += 1
        elif tag == 15:
            i += 4
        else:
            raise ValueError("unknown constant tag %d" % tag)
        index += 1
    _, this_i, super_i, n_ifaces = struct.unpack(">HHHH", data[i:i + 8])
    i += 8
    interfaces = []
    for _ in range(n_ifaces):
        interfaces.append(pool[pool[struct.unpack(">H", data[i:i + 2])[0]]])
        i += 2

    def attributes(i, key):
        n = struct.unpack(">H", data[i:i + 2])[0]
        i += 2
        for _ in range(n):
            name_i, length = struct.unpack(">HI", data[i:i + 6])
            if signatures is not None and pool.get(name_i) == "Signature":
                signatures[key] = pool[struct.unpack(">H", data[i + 6:i + 8])[0]]
            i += 6 + length
        return i

    def members(i, kind):
        n = struct.unpack(">H", data[i:i + 2])[0]
        i += 2
        out = []
        for _ in range(n):
            access, name_i, desc_i = struct.unpack(">HHH", data[i:i + 6])
            if signatures is not None:
                signatures[("access", kind, pool[name_i], pool[desc_i])] = access
            i = attributes(i + 6, (kind, pool[name_i], pool[desc_i]))
            out.append((pool[name_i], pool[desc_i]))
        return out, i

    fields, i = members(i, "f")
    methods, i = members(i, "m")
    attributes(i, None)
    name = pool[pool[this_i]]
    super_name = pool[pool[super_i]] if super_i else None
    return name, super_name, interfaces, set(fields), set(methods)


class HostJar:
    """The declared members and supertypes of every Minecraft class in one host jar."""

    def __init__(self, path, prefixes=("net/minecraft/", "com/mojang/")):
        self.classes = {}
        self.signatures = {}
        with zipfile.ZipFile(path) as archive:
            for entry in archive.namelist():
                if entry.endswith(".class") and (not prefixes or entry.startswith(prefixes)):
                    signatures = {}
                    name, sup, ifaces, fields, methods = parse_class(archive.read(entry), signatures)
                    self.classes[name] = (sup, ifaces, fields, methods)
                    self.signatures[name] = signatures

    def signature(self, owner, kind, name, desc):
        """The member's generic signature, or its descriptor when it has none; None when absent."""
        info = self.classes.get(owner)
        if info is None or (name, desc) not in (info[2] if kind == "f" else info[3]):
            return None
        return self.signatures.get(owner, {}).get((kind, name, desc), desc)

    def is_static(self, owner, kind, name, desc):
        """Whether the member is static, or None when the class does not declare it."""
        access = self.signatures.get(owner, {}).get(("access", kind, name, desc))
        return None if access is None else bool(access & 0x0008)

    def class_signature(self, owner):
        if owner not in self.classes:
            return None
        return self.signatures.get(owner, {}).get(None, "")

    def has_class(self, name):
        return name in self.classes

    def declares(self, owner, kind, name, desc):
        info = self.classes.get(owner)
        if info is None:
            return False
        return (name, desc) in (info[2] if kind == "f" else info[3])

    def names_on(self, owner, kind, name):
        info = self.classes.get(owner)
        if info is None:
            return set()
        return {d for n, d in (info[2] if kind == "f" else info[3]) if n == name}


def is_anonymous(name):
    return any(part[:1].isdigit() for part in name.split("$")[1:])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("maps")
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument("--table", default="src/main/resources/intermediary-to-mojang.tsv")
    parser.add_argument("--moves", default="src/main/resources/mojang-class-moves-26.1.tsv")
    parser.add_argument("--host-jar", action="append", default=[])
    parser.add_argument("--source-jar", action="append", default=[],
                        help="<version>=<obfuscated merged jar>, for comparing generic signatures")
    parser.add_argument("--out")
    parser.add_argument("--report")
    args = parser.parse_args()
    if args.fetch:
        fetch(args.maps)
        return

    data = {v: read_version(os.path.join(args.maps, v)) for v in VERSIONS}
    for v in VERSIONS:
        print(f"{v}: {len(data[v][0])} classes, {len(data[v][1])} members", file=sys.stderr)
    table = read_tsv_table(args.table)
    moves = read_moves(args.moves)
    jars = {}
    for spec in args.host_jar:
        label, path = spec.split("=", 1)
        jars[label] = HostJar(path)
    missing = [h for h in UNOBFUSCATED if h not in jars]
    if missing:
        sys.exit("missing --host-jar for " + ", ".join(missing))

    # Each version's class name by intermediary id; 26.x names come from the table, checked on the jar.
    names = {v: dict(data[v][0]) for v in VERSIONS}
    last = VERSIONS[-1]
    for host in UNOBFUSCATED:
        jar = jars[host]
        resolved = {}
        for cid, old in names[last].items():
            for candidate in (table["CLASS"].get(cid), moves.get(old), old):
                if candidate and jar.has_class(candidate):
                    resolved[cid] = candidate
                    break
        names[host] = resolved

    def class_mapper(source, host):
        by_name = {name: cid for cid, name in names[source].items()}

        def mapped(name):
            cid = by_name.get(name)
            if cid is None:
                return name if not name.startswith(("net/minecraft/", "com/mojang/")) else None
            return names[host].get(cid)
        return mapped

    # Each member's owner, name and descriptor in every version, by intermediary identity.
    member_names = {v: data[v][1] for v in VERSIONS}
    for host in UNOBFUSCATED:
        jar = jars[host]
        mapped_class = class_mapper(last, host)
        resolved = {}
        for key, (owner, name, desc) in member_names[last].items():
            cid, kind, mid = key
            host_owner = names[host].get(cid)
            host_desc = map_desc(desc, mapped_class)
            if host_owner is None or host_desc is None:
                continue
            candidates = [name]
            hinted = table["FIELD" if kind == "f" else "METHOD"].get(mid)
            if hinted and hinted != name:
                candidates.append(hinted)
            for candidate in candidates:
                if jar.declares(host_owner, kind, candidate, host_desc):
                    resolved[key] = (host_owner, candidate, host_desc)
                    break
            else:
                # Kept for the descriptor report: the host has the hinted name with another shape.
                for candidate in candidates:
                    if candidate != name and jar.names_on(host_owner, kind, candidate):
                        resolved[key] = (host_owner, candidate, None)
                        break
        member_names[host] = resolved

    all_versions = VERSIONS + UNOBFUSCATED
    jar_versions = {h: jars[h] for h in UNOBFUSCATED}
    source_jars = {}
    for spec in args.source_jar:
        version, path = spec.split("=", 1)
        source_jars[version] = HostJar(path, prefixes=())

    def signature_at(version, key):
        """A member's generic signature in that version's Mojang names, or None when unknown."""
        cid, kind, mid = key
        if version in jar_versions:
            value = member_names[version].get(key)
            if not value or value[2] is None:
                return None
            return jar_versions[version].signature(value[0], kind, value[1], value[2])
        if version not in source_jars:
            return None
        value = member_names[version].get(key)
        obf_to_moj, obfuscated = data[version][2]
        found = obfuscated.get((value[0], kind, value[1], value[2])) if value else None
        if not found:
            return None
        signature = source_jars[version].signature(found[0], kind, found[1], found[2])
        return None if signature is None else map_signature(signature, lambda n: obf_to_moj.get(n, n))

    def static_at(version, key):
        """Whether a member is static in that version, or None when unknown."""
        cid, kind, mid = key
        value = member_names[version].get(key)
        if not value or value[2] is None:
            return None
        if version in jar_versions:
            return jar_versions[version].is_static(value[0], kind, value[1], value[2])
        if version not in source_jars:
            return None
        found = data[version][2][1].get((value[0], kind, value[1], value[2]))
        return source_jars[version].is_static(found[0], kind, found[1], found[2]) if found else None

    def class_signature_at(version, cid):
        name = names[version].get(cid)
        if name is None:
            return None
        if version in jar_versions:
            return jar_versions[version].class_signature(name)
        if version not in source_jars:
            return None
        obf_to_moj = data[version][2][0]
        obf = next((o for o, m in obf_to_moj.items() if m == name), None)
        signature = source_jars[version].class_signature(obf) if obf else None
        return None if signature is None else map_signature(signature, lambda n: obf_to_moj.get(n, n))

    # Where a member or class is known in both, the newest comparable version decides.
    comparable = [v for v in reversed(all_versions) if v in jar_versions or v in source_jars]

    def generics_changed(source, later_signature_of, source_signature):
        """Whether the type arguments changed beyond class renames between source and a later version."""
        if source_signature is None:
            return False
        for version in comparable:
            if ORDER[version] <= ORDER[source]:
                break
            later = later_signature_of(version)
            if later is None:
                continue
            mapped = map_signature(source_signature, class_mapper(source, version))
            return mapped != later
        return False

    generic_checked = {"members": 0, "classes": 0}
    generic_dropped = []
    static_dropped = []

    # Every class name, and every owner, name and descriptor, that some version has under any identity.
    def class_exists(version, name):
        if version in jar_versions:
            return jar_versions[version].has_class(name)
        return name in class_names_by_version[version]

    def member_exists(version, kind, owner, name, desc):
        if version in jar_versions:
            return jar_versions[version].declares(owner, kind, name, desc)
        return (kind, owner, name, desc) in member_keys_by_version[version]

    class_names_by_version = {v: set(names[v].values()) for v in VERSIONS}
    member_keys_by_version = {v: {(k[1],) + value for k, value in member_names[v].items()} for v in VERSIONS}

    def versions_between(first, host):
        """The versions from first up to and including host."""
        return [v for v in all_versions if ORDER[first] <= ORDER[v] <= ORDER[host]]

    rows, desc_changed, class_rows, rebuilt, reused = [], [], [], [], 0
    members_by_class = {v: defaultdict(set) for v in all_versions}
    for v in all_versions:
        for (cid, kind, mid), value in member_names[v].items():
            if "$" not in value[1] and value[2] is not None:
                members_by_class[v][cid].add((kind, mid))

    def kept_share(cid, source, host):
        """The share of a class's members at the source version that the host still has."""
        before = members_by_class[source].get(cid, set())
        if not before:
            return 1.0
        after = members_by_class[host].get(cid, set())
        return len(before & after) / len(before)

    # Class renames: the name a mod carries for a class id differs from the host's name for it.
    for v_index, source in enumerate(VERSIONS):
        for cid, name in names[source].items():
            if is_anonymous(name):
                continue
            if v_index > 0 and names[VERSIONS[v_index - 1]].get(cid) == name:
                continue
            until = next((v for v in all_versions[v_index + 1:] if names[v].get(cid) != name), None)
            if until is None:
                continue
            from_v = "*" if v_index == 0 else source
            for host in HOSTS:
                if ORDER[host] < ORDER[until]:
                    continue
                new = names[host].get(cid)
                if new is None:
                    # Moved again by a host this script cannot name: the newest known name lets the
                    # shims for that move take it from there.
                    earlier = [v for v in all_versions if ORDER[until] <= ORDER[v] < ORDER[host]
                               and names[v].get(cid)]
                    new = names[earlier[-1]][cid] if earlier else None
                if new is None or new == name:
                    continue
                # The old name back on any version since the rename belongs to another class there.
                if any(class_exists(v, name) for v in versions_between(until, host)):
                    reused += 1
                    continue
                # A class whose type parameters or supertypes' arguments changed means something else.
                checked_source = next((v for v in (source, "1.21.1", "1.21.11") if v in source_jars
                                       and names[v].get(cid) == name), None)
                if checked_source is not None:
                    generic_checked["classes"] += 1
                    if generics_changed(checked_source, lambda v: class_signature_at(v, cid),
                                        class_signature_at(checked_source, cid)):
                        generic_dropped.append(("c", name, "-", "-", new, host))
                        continue
                # A class whose members mostly did not survive was rebuilt, not renamed.
                kept = kept_share(cid, source, host if ORDER[host] <= ORDER["26.1"] else "26.1")
                if kept < KEPT_MEMBERS:
                    rebuilt.append((name, new, from_v, until, host, kept))
                    continue
                class_rows.append((name, new, from_v, until, host))

    # Member renames, keyed by what a mod built for the source version carries.
    for v_index, source in enumerate(VERSIONS):
        for key, (owner, name, desc) in member_names[source].items():
            cid, kind, mid = key
            if "$" in name or name.startswith("<") or is_anonymous(owner) or (owner, name, desc) in CHANGED_MEANING:
                continue
            if v_index > 0 and member_names[VERSIONS[v_index - 1]].get(key) == (owner, name, desc):
                continue
            until = next((v for v in all_versions[v_index + 1:]
                          if member_names[v].get(key) != (owner, name, desc)), None)
            if until is None:
                continue
            from_v = "*" if v_index == 0 else source
            for host in HOSTS:
                if ORDER[host] < ORDER[until]:
                    continue
                target, carried = member_names[host].get(key), False
                if target is None or target[2] is None:
                    # Gone from the host: the newest name it had lets a later shim keyed on it apply.
                    earlier = [v for v in all_versions if ORDER[until] <= ORDER[v] < ORDER[host]
                               and member_names[v].get(key) and member_names[v][key][2] is not None]
                    if not earlier:
                        if target is not None:
                            desc_changed.append((kind, owner, name, desc, target[0], target[1], "?",
                                                 from_v, until, host))
                        continue
                    target, carried = member_names[earlier[-1]][key], True
                host_owner, new_name, host_desc = target
                if new_name == name:
                    continue
                mapped_desc = map_desc(desc, class_mapper(source, earlier[-1] if carried else host))
                if mapped_desc != host_desc:
                    desc_changed.append((kind, owner, name, desc, host_owner, new_name, host_desc,
                                         from_v, until, host))
                    continue
                if names[earlier[-1] if carried else host].get(cid) != host_owner:
                    continue
                # A rename whose type arguments changed means something else, such as List<Player>
                # becoming List<ContainerUser> under the same erased List.
                checked_source = next((v for v in (source, "1.21.1", "1.21.11") if v in source_jars
                                       and member_names[v].get(key) == (owner, name, desc)), None)
                if checked_source is not None:
                    generic_checked["members"] += 1
                    if generics_changed(checked_source, lambda v: signature_at(v, key),
                                        signature_at(checked_source, key)):
                        generic_dropped.append((kind, owner, name, desc, new_name, host))
                        continue
                    # A method that turned static or instance takes its receiver differently.
                    was_static = static_at(checked_source, key)
                    now_static = next((static_at(v, key) for v in comparable
                                       if ORDER[v] > ORDER[checked_source] and static_at(v, key) is not None), None)
                    if was_static is not None and now_static is not None and was_static != now_static:
                        static_dropped.append((kind, owner, name, desc, new_name, host))
                        continue
                # The old key on any version since the rename names another member there.
                if any(member_exists(v, kind, host_owner, name, host_desc) or
                       member_exists(v, kind, owner, name, desc) for v in versions_between(until, host)):
                    reused += 1
                    continue
                rows.append(("FIELD" if kind == "f" else "METHOD", owner, name, desc, new_name,
                             host_owner, host_desc, from_v, until, host))

    # A rename whose new name is another row's old name for the same host would be applied twice by
    # a transform that repeats its passes until nothing changes, so both rows are left out.
    old_keys = defaultdict(list)
    for row in rows:
        old_keys[(row[9], row[0], row[1], row[2], row[3])].append(row)
    chained = set()
    for row in rows:
        for other in old_keys.get((row[9], row[0], row[5], row[4], row[6]), ()):
            if other is not row:
                chained.add(row)
                chained.add(other)
    rows = [r for r in rows if r not in chained]

    # A class the curated 26.1 move table already sends somewhere else keeps that move.
    conflicts = [r for r in class_rows if r[0] in moves and moves[r[0]] != r[1]]
    class_rows = [r for r in class_rows if r[0] not in moves]

    merged = merge_hosts(rows, lambda r: r[:9], 9)
    merged_classes = merge_hosts(class_rows, lambda r: r[:4], 4)
    lines = []
    for (old, new, from_v, until), host_from, host_until in merged_classes:
        lines.append("\t".join(("CLASS", old, "-", "-", new, "=", "=", from_v, until, host_from, host_until)))
    for (kind, owner, name, desc, new, host_owner, host_desc, from_v, until), host_from, host_until in merged:
        lines.append("\t".join((kind, owner, name, desc, new,
                                "=" if host_owner == owner else host_owner,
                                "=" if host_desc == desc else host_desc,
                                from_v, until, host_from, host_until)))
    lines.sort()

    if args.out:
        with open(args.out, "w", encoding="utf-8") as out:
            out.write(HEADER)
            for line in lines:
                out.write(line + "\n")

    counts = defaultdict(int)
    for line in lines:
        counts[line.split("\t", 1)[0]] += 1
    merged_desc = merge_hosts(desc_changed, lambda r: r[:9], 9)
    desc_kinds = defaultdict(int)
    for key, _, _ in merged_desc:
        desc_kinds["FIELD" if key[0] == "f" else "METHOD"] += 1
    summary = [f"rows: {len(lines)}"] + [f"{k}: {v}" for k, v in sorted(counts.items())]
    summary.append("renamed with a changed descriptor (rows): "
                   + ", ".join(f"{k} {v}" for k, v in sorted(desc_kinds.items())))
    summary.append(f"left out because the old name names something else later: {reused}")
    summary.append(f"left out as chained renames: {len(chained)}")
    summary.append(f"class renames the 26.1 move table sends elsewhere: {len(conflicts)}")
    summary.append(f"classes left out as rebuilt (class and host pairs): {len(rebuilt)}")
    dropped_keys = {r[:5] for r in generic_dropped}
    summary.append(f"left out because they turned static or instance: {len({r[:5] for r in static_dropped})}")
    summary.append(f"left out because their type arguments changed: {len(dropped_keys)} "
                   f"(checked {generic_checked['members']} member and {generic_checked['classes']} class pairs)")
    print("\n".join(summary), file=sys.stderr)
    if args.report:
        os.makedirs(args.report, exist_ok=True)
        with open(os.path.join(args.report, "summary.txt"), "w") as out:
            out.write("\n".join(summary) + "\n")
        with open(os.path.join(args.report, "descriptor-changed.tsv"), "w") as out:
            out.write("# KIND\tOWNER\tNAME\tDESC\tHOST_OWNER\tHOST_NAME\tHOST_DESC\tFROM\tUNTIL\tHOST_FROM\tHOST_UNTIL\n")
            for key, host_from, host_until in sorted(merged_desc):
                out.write("\t".join(key + (host_from, host_until)) + "\n")
        with open(os.path.join(args.report, "class-rebuilt.tsv"), "w") as out:
            out.write("# OLD\tID_BASED_NEW\tFROM\tUNTIL\tHOST\tKEPT_SHARE\n")
            for old, new, from_v, until, host, kept in sorted(set(rebuilt)):
                out.write("\t".join((old, new, from_v, until, host, "%.2f" % kept)) + "\n")
        with open(os.path.join(args.report, "generics-changed.tsv"), "w") as out:
            out.write("# KIND\tOWNER\tNAME\tDESC\tNEW_NAME\n")
            for row in sorted({r[:5] for r in generic_dropped}):
                out.write("\t".join(row) + "\n")
            for row in sorted({r[:5] for r in static_dropped}):
                out.write("\t".join(row) + "\tstatic changed\n")
        with open(os.path.join(args.report, "class-move-conflicts.tsv"), "w") as out:
            out.write("# OLD\tID_BASED_NEW\tMOVE_TABLE_NEW\tFROM\tUNTIL\tHOST\n")
            for old, new, from_v, until, host in sorted(set(conflicts)):
                out.write("\t".join((old, new, moves[old], from_v, until, host)) + "\n")


HEADER = """# Mojang-name renames between the version a NeoForge or Forge mod was built for and its host.
# Generated by scripts/harvest-mojang-renames.py from Mojang's official mappings and Fabric
# intermediary (CC0-1.0) for 1.21 to 1.21.11, joined by intermediary id. 26.x names come from
# intermediary-to-mojang.tsv and are kept only where the 26.1.2, 26.2 or 26.3 client jar declares
# them. Do not edit by hand.
# A row applies to a mod built for a version in [FROM, UNTIL) on a host in [HOST_FROM, HOST_UNTIL).
# * is open. HOST_OWNER and HOST_DESC are = when unchanged. A CLASS row's NEW_NAME is the new class.
# KIND\tOWNER\tNAME\tDESC\tNEW_NAME\tHOST_OWNER\tHOST_DESC\tFROM\tUNTIL\tHOST_FROM\tHOST_UNTIL
"""


def merge_hosts(rows, key_of, host_index):
    """Folds rows that differ only by host into one row per run of consecutive hosts."""
    hosts_by_key = defaultdict(set)
    for row in rows:
        hosts_by_key[key_of(row)].add(row[host_index])
    merged = []
    for key, hosts in hosts_by_key.items():
        ordered = [h for h in HOSTS if h in hosts]
        start = prev = ordered[0]
        for host in ordered[1:] + [None]:
            if host is not None and HOSTS.index(host) == HOSTS.index(prev) + 1:
                prev = host
                continue
            following = HOSTS.index(prev) + 1
            merged.append((key, start, HOSTS[following] if following < len(HOSTS) else "*"))
            start = prev = host
    return merged


if __name__ == "__main__":
    main()
