#!/usr/bin/env python3
"""Harvest the MCP class names Forge mods for 1.14 to 1.16.5 use, each mapped to its class on 26.x hosts.

Forge 1.13 to 1.16.5 named Minecraft classes with MCPConfig's names (net/minecraft/entity/monster/ZombieEntity).
For 1.14.4 to 1.16.5, MCPConfig's joined.tsrg gives the obfuscated name of every MCP class, and three sources say
what it became:
  - its Fabric intermediary id of that version, named on 26.x hosts by intermediary-to-mojang.tsv;
  - its Mojang name of that version, also moved by mojang-class-moves-26.1.tsv;
  - its id's Mojang name in 1.21.11, where an id 1.21.11 lacks is a class removed by then.
Retromod's own shim chain carries every candidate to each host, as it does for a mod that names it, and the host jars
judge the result. A name is left out when:
  - its sources end on different classes, or its Mojang name now belongs to another class (a split such as Cow and
    AbstractCow). Where the chain moves the Mojang name, the chain wins only over an id whose class left the old type
    hierarchy (a text component that became a contents record, no longer a Component);
  - its id was gone by 1.21.11 but a host class kept the name (1.21.5's unrelated RenderPipeline);
  - only Retromod's rebuilt classes take it, or it meant different classes in different versions;
  - a host or a 1.17 or newer version uses the name for another class (MCP's RisingParticle is Mojang's
    BaseAshSmokeParticle), since some passes read class redirects without per-mod limits;
  - only its id reaches a class that kept under half its member names or lost a nearest supertype (rewritten);
  - the chain already redirects it, or forge-1.12.2-class-moves.tsv maps it.
Anonymous classes are skipped, since their numbers say nothing across versions.

Steps (ASM on the class path for the Java tool; every download is data, nothing from it runs):
  1. Per version 1.14.4, 1.15.1, 1.15.2, 1.16.1 to 1.16.5, a folder with MCPConfig's mcp_config.zip (Forge maven),
     Fabric's intermediary.jar, Mojang's client_mappings.txt and server_mappings.txt (piston-meta), and the
     client.jar and server.jar. A 1.21.11 folder with intermediary.jar and both mappings (--lineage). Mojang
     mappings of 1.17.1 to 1.21.11 (--mojang-names) and Yarn v2 jars of 1.14.4 to 1.21.11 (--yarn-names).
  2. Candidates: this script with <sources> and --candidates names.txt; the MCP names themselves are the first
     column of each version's joined.tsrg class lines (keys.txt).
  3. Probes: java scripts/McpClassNamesProbe.java make names.txt probe.jar 1.16.5, and the same for keys.txt. Run
     both through a Retromod CLI built WITHOUT forge-mcp-class-names.tsv (batch --target <mc> --target-loader
     <loader> --mc-jar <host jar>), once per host, then McpClassNamesProbe.java read <out.jar> names.txt > <host>.tsv.
  4. Hierarchies: McpClassNamesProbe.java members <host jar>,<joml jar> per host (--host-members), and
     members <client.jar>,<server.jar> per old version (--old-members).
  5. This script with every option below and --out src/main/resources/retromod/forge-mcp-class-names.tsv.

Usage:
  harvest-mcp-class-names.py <sources> --candidates <names.txt>
  harvest-mcp-class-names.py <sources> --probe <host>=<tsv> ... --keys-probe <host>=<tsv> ...
      --host-members <host>=<tsv> ... --old-members <mc>=<tsv> ... --mojang-names <txt> ...
      --yarn-names <jar> ... --out <tsv> [--report <txt>] [--explain <mcp name>]
  <sources>: --version <mc>=<dir> ... --lineage <dir> --intermediary-map <tsv> --moves <tsv>
      --host <name>=<jar>[,<jar>...] ... --host-version <name>:<mc> ... --existing <tsv> ...
A probe file has one "name TAB name after translation" line per name, from a translation for that host.
"""
import argparse
import collections
import re
import zipfile

ANONYMOUS = re.compile(r"\$\d")
REBUILT = "com/retromod/"


def version_key(v):
    return tuple(int(p) for p in v.split("."))


def read_tsrg_classes(zip_path):
    """MCP class name -> obfuscated name, from MCPConfig's joined.tsrg."""
    out = {}
    with zipfile.ZipFile(zip_path) as z, z.open("config/joined.tsrg") as f:
        for raw in f:
            line = raw.decode("utf-8")
            if line.startswith(("\t", " ", "#")) or not line.strip():
                continue
            parts = line.split()
            if len(parts) >= 2:
                out[parts[1]] = parts[0]
    return out


def read_intermediary_classes(jar_path):
    """Obfuscated name -> intermediary name, from Fabric's tiny v1 file."""
    out = {}
    with zipfile.ZipFile(jar_path) as z, z.open("mappings/mappings.tiny") as f:
        for raw in f:
            parts = raw.decode("utf-8").rstrip("\n").split("\t")
            if parts[0] == "CLASS" and len(parts) >= 3:
                out[parts[1]] = parts[2]
    return out


MEMBER = re.compile(r"^\s+(?:\d+:\d+:)?\S+ ([\w$<>]+)(?:\(.*\))? -> \S+$")
SYNTHETIC = re.compile(r"^(<init>|<clinit>|lambda\$.*|access\$.*|values|valueOf|\$values|\$VALUES"
                       r"|\$deserializeLambda\$)$")


def read_mojang_classes(paths, members=None):
    """Obfuscated name -> Mojang name from ProGuard client and server mappings; fills members[obf] with names."""
    out = {}
    for path in paths:
        current = None
        with open(path, encoding="utf-8") as f:
            for line in f:
                if line.startswith("#") or " -> " not in line:
                    continue
                if not line.startswith(" "):
                    named, current = line.rstrip().rstrip(":").split(" -> ")
                    out[current] = named.replace(".", "/")
                    continue
                match = MEMBER.match(line.rstrip())
                if members is not None and match and not SYNTHETIC.match(match.group(1)):
                    members.setdefault(current, set()).add(match.group(1))
    return out


def read_host_members(path):
    """Class -> (super, interfaces, member names), from tools' HostMembers dump."""
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            name, sup, itfs, members = line.rstrip("\n").split("\t")
            out[name] = (sup, [i for i in itfs.split(",") if i], set(members.split(",")) if members else set())
    return out


def supertypes(hierarchy, name):
    """Every supertype of a class that the hierarchy knows."""
    seen = set()
    todo = [hierarchy[name][0]] + hierarchy[name][1] if name in hierarchy else []
    while todo:
        current = todo.pop()
        if not current or current in seen or current not in hierarchy:
            continue
        seen.add(current)
        sup, itfs, _ = hierarchy[current]
        todo += [sup] + itfs
    return seen


def inherited_members(hierarchy, name):
    """The member names a class declares or inherits from classes the hierarchy knows."""
    seen, todo, out = set(), [name], set()
    while todo:
        current = todo.pop()
        if current in seen or current not in hierarchy:
            continue
        seen.add(current)
        sup, itfs, members = hierarchy[current]
        out |= members
        todo += [sup] + itfs
    return out


def read_columns(path, kind=None):
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or "\t" not in line:
                continue
            parts = line.rstrip("\n").split("\t")
            if kind is not None:
                if parts[0] != kind or len(parts) < 3:
                    continue
                parts = parts[1:]
            out[parts[0].strip()] = parts[1].strip()
    return out


def read_jar_classes(paths):
    out = set()
    for path in paths.split(","):
        with zipfile.ZipFile(path) as z:
            out.update(n[:-6] for n in z.namelist() if n.endswith(".class"))
    return out


def read_yarn_classes(jar_path):
    """Every named class in a Yarn tiny v2 jar."""
    with zipfile.ZipFile(jar_path) as z, z.open("mappings/mappings.tiny") as f:
        return {parts[2] for parts in (raw.decode("utf-8").rstrip("\n").split("\t") for raw in f)
                if parts[0] == "c" and len(parts) >= 3}


def read_lineage(folder):
    """Intermediary id -> Mojang name in the version of that folder (1.21.11, the last one with both)."""
    if not folder:
        return None
    by_obf = read_intermediary_classes(f"{folder}/intermediary.jar")
    mojang = read_mojang_classes([f"{folder}/client_mappings.txt", f"{folder}/server_mappings.txt"])
    return {ident: mojang[obf] for obf, ident in by_obf.items() if obf in mojang}


def candidates_per_version(versions, id_to_host, moves, lineage_names):
    """MCP name -> list of (version, intermediary id, candidates in preference order)."""
    out = collections.defaultdict(list)
    for mc, folder in versions:
        mcp = read_tsrg_classes(f"{folder}/mcp_config.zip")
        intermediary = read_intermediary_classes(f"{folder}/intermediary.jar")
        members = {}
        mojang = read_mojang_classes([f"{folder}/client_mappings.txt", f"{folder}/server_mappings.txt"], members)
        for name, obf in mcp.items():
            if ANONYMOUS.search(name):
                continue
            ident = intermediary.get(obf)
            by_id = id_to_host.get(ident) if ident else None
            raw = mojang.get(obf)
            by_name = [moves[raw], raw] if raw in moves else [raw] if raw else []
            out[name].append((mc, ident, by_id, by_name))
            if lineage_names is not None and ident and ident.startswith("net/minecraft/class_"):
                # Ids are never reused, so an id 1.21.11 lacks is a class removed by then.
                LINEAGE[(name, mc)] = lineage_names.get(ident, REMOVED)
            MEMBERS[(name, mc)] = members.get(obf, set())
            OBFUSCATED[(name, mc)] = obf
        INTERMEDIARY[mc] = intermediary
    return out


# Per MCP name and version: its Mojang member names and obfuscated name; per version: obfuscated -> intermediary.
MEMBERS, OBFUSCATED, INTERMEDIARY = {}, {}, {}
# Per MCP name and version: the Mojang name its id had in 1.21.11, or REMOVED.
LINEAGE, REMOVED = {}, "-"
# A class only its intermediary id reaches must keep at least this share of its member names on every host.
MIN_KEPT_MEMBERS = 0.5


def judge(ident, by_id, by_name, final, classes, host_to_ids):
    """The class on this host, the candidates that reach it, and the id's other class when the chain moved the name.

    The class is "disagree", "rebuilt" or None when there is none."""
    id_end = final(by_id) if by_id else None
    rebuilt = bool(id_end and id_end.startswith(REBUILT)) or any(final(c).startswith(REBUILT) for c in by_name)
    if id_end not in classes:
        id_end = None
    raw = by_name[-1] if by_name else None
    name_end = final(raw) if raw else None
    if name_end not in classes:
        name_end = None
    if name_end and name_end != raw:
        # The chain moves the class's own Mojang name, as it does for a mod of that version. Where the id goes
        # elsewhere, the caller decides: the chain wins only over an id whose class was rewritten.
        rival = id_end if id_end and id_end != name_end else None
        return name_end, [c for c in [by_id] + by_name if c and final(c) == name_end], rival
    elif name_end:
        owners = host_to_ids.get(name_end)
        reused = ident and owners and ident not in owners
        if id_end and id_end != name_end:
            # The Mojang name now belongs to another class (a split such as Cow and AbstractCow), or the sources differ.
            return "disagree", [(by_id, id_end), (raw, name_end)], None
        if reused and not id_end:
            return None, [], None
        end = name_end
    elif id_end:
        end = id_end
    else:
        return ("rebuilt" if rebuilt else None), [], None
    return end, [c for c in [by_id] + by_name if c and final(c) == end], None


def check_lineage(name, mc, by_name, end, final, classes):
    """The end again when the class's 1.21.11 lineage agrees, None when its id was gone by then, else "disagree"."""
    lineage = LINEAGE.get((name, mc))
    raw = by_name[-1] if by_name else None
    # The chain moving the class's own Mojang name is a decision made for that class.
    if lineage is None or (raw and final(raw) != raw and final(raw) == end):
        return end
    if lineage == REMOVED:
        # A host class under the old name is a reused name, such as 1.21.5's unrelated RenderPipeline.
        return None
    return end if final(lineage) == end else "disagree"


def continuity(name, mc, on_hosts, hierarchies, old_hierarchies, host_classes, finals, id_to_host):
    """None when the class kept half its member names and its nearest supertypes on every host, else why not."""
    old_members = MEMBERS.get((name, mc), set())
    if old_members and hierarchies:
        kept = min(len(old_members & inherited_members(hierarchies[h], end)) for h, end in on_hosts.items())
        if kept / len(old_members) < MIN_KEPT_MEMBERS:
            return f"{kept}/{len(old_members)} members kept"
    lost = lost_supertypes(name, mc, on_hosts, old_hierarchies.get(mc), hierarchies, host_classes, finals, id_to_host)
    return f"no longer a {sorted(lost)[0]}" if lost else None


def lost_supertypes(name, mc, ends, old_hierarchy, hierarchies, host_classes, finals, id_to_host):
    """The host names of the class's nearest Minecraft supertypes that its host class no longer has."""
    obf = OBFUSCATED[(name, mc)]
    if not old_hierarchy or not hierarchies or obf not in old_hierarchy:
        return set()
    lost, seen = set(), set()
    sup, itfs, _ = old_hierarchy[obf]
    todo = [sup] + itfs
    while todo:
        old_super = todo.pop()
        if not old_super or old_super in seen:
            continue
        seen.add(old_super)
        host_name = id_to_host.get(INTERMEDIARY[mc].get(old_super))
        on_hosts = {host: finals[host](host_name) for host in ends} if host_name else {}
        if not on_hosts or any(end not in host_classes[host] for host, end in on_hosts.items()):
            # A supertype the host lacks: its own supertypes stand in for it.
            if old_super in old_hierarchy:
                todo += [old_hierarchy[old_super][0]] + old_hierarchy[old_super][1]
            continue
        # The nearest supertype the host has; an ancestor further up can drop an interface for every subclass at once.
        for host, end in ends.items():
            if on_hosts[host] != end and on_hosts[host] not in supertypes(hierarchies[host], end):
                lost.add(on_hosts[host])
    return lost


def host_label(name, host_version):
    """A readable host name, such as NeoForge 26.3 for neo263."""
    loader = "NeoForge" if name.startswith("neo") else "Forge" if name.startswith("forge") else name
    return f"{loader} {host_version.get(name, '')}".strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", action="append", required=True)
    ap.add_argument("--intermediary-map", required=True)
    ap.add_argument("--moves", required=True)
    ap.add_argument("--host", action="append", required=True)
    ap.add_argument("--existing", action="append", default=[])
    ap.add_argument("--candidates")
    ap.add_argument("--probe", action="append", default=[])
    ap.add_argument("--out")
    ap.add_argument("--report")
    ap.add_argument("--lineage", help="dir with 1.21.11 intermediary.jar and Mojang mappings: where each id went")
    ap.add_argument("--keys-probe", action="append", default=[],
                    help="<host>=<tsv>: what Retromod already makes of each MCP name for a 1.16.5 mod on that host")
    ap.add_argument("--host-members", action="append", default=[],
                    help="<host>=<tsv>: every class of that host with its super, interfaces and member names")
    ap.add_argument("--old-members", action="append", default=[],
                    help="<mc>=<tsv>: the same dump for that version's client and server jars, in obfuscated names")
    ap.add_argument("--mojang-names", action="append", default=[],
                    help="ProGuard mappings of a 1.17 or newer version, whose class names a Mojang-named mod may use")
    ap.add_argument("--yarn-names", action="append", default=[],
                    help="a Yarn v2 jar: Forge mods built with Loom keep Yarn class names in mixin selectors")
    ap.add_argument("--host-version", action="append", default=[], help="<host name>:<mc version>")
    ap.add_argument("--explain", action="append", default=[], help="print every candidate of this MCP name")
    args = ap.parse_args()

    id_to_host = read_columns(args.intermediary_map, kind="CLASS")
    host_to_ids = collections.defaultdict(set)
    for ident, name in id_to_host.items():
        host_to_ids[name].add(ident)
    moves = read_columns(args.moves)
    versions = sorted((v.split("=", 1) for v in args.version), key=lambda v: version_key(v[0]))
    per_name = candidates_per_version(versions, id_to_host, moves, read_lineage(args.lineage))

    if args.candidates:
        names = set()
        for entries in per_name.values():
            for _, _, by_id, by_name in entries:
                names.update(c for c in [by_id] + by_name if c)
        names.update(lineage for lineage in LINEAGE.values() if lineage != REMOVED)
        with open(args.candidates, "w", encoding="utf-8") as f:
            f.write("\n".join(sorted(names)) + "\n")
        print(f"{len(names)} candidates for {len(per_name)} MCP names")
        return

    hosts = [h.split("=", 1) for h in args.host]
    host_classes = {mc: read_jar_classes(jar) for mc, jar in hosts}
    probes = {mc: read_columns(path) for mc, path in (p.split("=", 1) for p in args.probe)}
    finals = {host: (lambda c, p=probes.get(host, {}): p.get(c, c)) for host, _ in hosts}
    already = {mc: read_columns(path) for mc, path in (p.split("=", 1) for p in args.keys_probe)}
    hierarchies = {mc: read_host_members(path) for mc, path in (p.split("=", 1) for p in args.host_members)}
    old_hierarchies = {mc: read_host_members(path) for mc, path in (p.split("=", 1) for p in args.old_members)}
    host_version = dict(h.split(":", 1) for h in args.host_version)
    # A name a newer mod may use: any host class, any class name a 1.17 or newer version had, and any Yarn name,
    # which a Forge mod built with Loom keeps in its mixin selectors and refmap keys.
    mojang_named = set().union(*host_classes.values())
    for path in args.mojang_names:
        mojang_named.update(read_mojang_classes([path]).values())
    yarn_named = set()
    for path in args.yarn_names:
        yarn_named.update(read_yarn_classes(path))
    existing = {}
    for path in args.existing:
        existing.update(read_columns(path))

    for name in args.explain:
        for mc, ident, by_id, by_name in per_name.get(name, []):
            print(f"{name} {mc} id={ident} by_id={by_id} by_name={by_name}")
            for host, classes in host_classes.items():
                probe = probes.get(host, {})
                for c in [by_id] + by_name:
                    if c:
                        end = probe.get(c, c)
                        found = "exists" if end in classes else "MISSING"
                        print(f"   {host}: {c} -> {end} {found} ids={sorted(host_to_ids.get(end, []))[:3]}")
    if args.explain:
        return

    rows, report = [], collections.defaultdict(list)
    for name in sorted(per_name):
        entries = per_name[name]
        first = min((e[0] for e in entries), key=version_key)
        ends, missing_on, rebuilt_on, conflict, rivals = {}, [], set(), None, {}
        for host, classes in host_classes.items():
            final = finals[host]
            host_ends = set()
            for mc, ident, by_id, by_name in entries:
                end, reaching, rival = judge(ident, by_id, by_name, final, classes, host_to_ids)
                if rival:
                    rivals[host] = rival
                if end == "disagree":
                    conflict = f"{mc} on {host}: {reaching}"
                    break
                if end not in (None, "rebuilt"):
                    end = check_lineage(name, mc, by_name, end, final, classes)
                    if end == "disagree":
                        conflict = f"{mc} on {host}: 1.21.11 lineage {LINEAGE[(name, mc)]} ends elsewhere"
                        break
                if end == "rebuilt":
                    rebuilt_on.add(host)
                elif end is not None:
                    host_ends.add(end)
            if conflict:
                break
            if len(host_ends) > 1:
                conflict = f"versions on {host}: {sorted(host_ends)}"
                break
            if host_ends:
                ends[host] = host_ends.pop()
            else:
                missing_on.append(host)
        if conflict:
            report["ambiguous"].append(f"{name}: {conflict}")
            continue
        if not ends:
            report["rebuilt-by-retromod" if rebuilt_on else "unresolved"].append(name)
            continue
        # The row stops at the first host version it does not reach, so the hosts it misses must be the newest.
        until = "*"
        if missing_on:
            first_missing = min(host_version[h] for h in missing_on)
            if any(version_key(host_version[h]) >= version_key(first_missing) for h in ends):
                report["missing-on-mixed-hosts"].append(f"{name}: {ends} missing on {missing_on}")
                continue
            until = first_missing
            report["stops-before-newest-host"].append(f"{name}: until {until} ({'rebuilt' if rebuilt_on else 'gone'})")
        # One candidate must end on the agreed class on every host, so the chain carries it the same way.
        candidates = []
        for mc, ident, by_id, by_name in sorted(entries, key=lambda e: version_key(e[0]), reverse=True):
            candidates += [c for c in [by_id] + by_name if c and c not in candidates]
        target = next((c for c in candidates if all(finals[h](c) == end for h, end in ends.items())), None)
        if target is None:
            report["no-common-candidate"].append(f"{name}: {ends}")
            continue
        # A name that is already the 26.1 name is carried by the chain as it is.
        if target == name or all(end == name for end in ends.values()):
            continue
        # A name another redirect already takes keeps it; a limit added here would apply to that redirect too.
        today = {h: already[h].get(name, name) for h in already if already[h].get(name, name) != name}
        if today:
            same = all(finals[h](today[h]) == ends.get(h) for h in today)
            kind = "already-redirected" if same else "already-redirected-elsewhere"
            report[kind].append(f"{name}: today {today}, this era {ends}")
            continue
        if name in existing:
            # The 1.12.2 table keeps its key; a different class there is a name both eras used.
            if any(finals[h](existing[name]) != end for h, end in ends.items()):
                report["existing-table-differs"].append(f"{name}: 1.12.2 table {existing[name]}, this era {target}")
            continue
        # A Mojang-named mod's bytecode may name a host or newer class this MCP name now stands for something else.
        # Several passes read class redirects without the per-mod limits, so such a name is left out.
        if name in mojang_named and not any(by_name and by_name[-1] == name for _, _, _, by_name in entries):
            report["mojang-name-of-another-class"].append(f"{name} -> {target}")
            continue
        live = name in mojang_named or name in yarn_named
        # Whether the class's Mojang name of its own version reaches the same class, or only its intermediary id does.
        via_name = any(all(finals[h](c) == end for h, end in ends.items())
                       for _, _, _, by_name in entries for c in by_name)
        latest = max((e[0] for e in entries), key=version_key)
        check = lambda on_hosts: continuity(name, latest, on_hosts, hierarchies, old_hierarchies, host_classes, finals,
                                            id_to_host)
        if rivals:
            # The chain moved the name one way and the id went another. Unless the id's class left the old type
            # hierarchy (a text component that became a contents record, no longer a Component), that is a split
            # such as Cow and AbstractCow, and the name is left out.
            if not lost_supertypes(name, latest, rivals, old_hierarchies.get(latest), hierarchies, host_classes,
                                   finals, id_to_host):
                report["chain-and-id-split"].append(f"{name}: chain {target}, id {sorted(set(rivals.values()))}")
                continue
            report["chain-over-rewritten-id"].append(f"{name} -> {target}: id {sorted(set(rivals.values()))}")
        if not via_name:
            # Only the id follows this class, so its members and place must have stayed: a reworked class
            # (a text component that became a contents record) shares its lineage but not its API.
            reason = check(ends)
            if reason:
                report["id-only-rewritten"].append(f"{name} -> {target}: {reason}")
                continue
            report["id-only-renamed"].append(f"{name} -> {target}")
        rows.append((name, target, "host" if live else "", first, until))

    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write("# Forge 1.14 to 1.16.5 (MCP) class names -> the class on 26.x hosts. Generated by\n")
            f.write("# scripts/harvest-mcp-class-names.py from MCPConfig (obfuscated <-> MCP class), Fabric\n")
            f.write("# intermediary (CC0-1.0) and Mojang's official mappings of\n")
            f.write("# " + ", ".join(v for v, _ in versions) + ",\n")
            f.write("# joined on obfuscated names. Retromod's shim chain carries each target to the class these hosts\n")
            f.write("# have: " + ", ".join(host_label(h, host_version) for h, _ in hosts) + ".\n")
            f.write("# MCPConfig data (c) 2018 Forge Development LLC, under the license in META-INF/licenses/mcpconfig;\n")
            f.write("# this table is an altered work derived from it. Do not edit by hand.\n")
            f.write("# Names forge-1.12.2-class-moves.tsv already maps are left out.\n")
            f.write("# LIVE is 'host' when a 26.x host, a 1.17 to 1.21.11 version or Yarn has a class under the MCP\n")
            f.write("# name too, so only a mod known to be built before 1.17 takes the row. FIRST is the first\n")
            f.write("# version whose MCPConfig has the name.\n")
            f.write("# UNTIL_HOST is the first host the row does not apply to, because the chain no longer ends\n")
            f.write("# on a host class there (* applies to every 26.x host).\n")
            f.write("# MCP_NAME\tTARGET\tLIVE\tFIRST\tUNTIL_HOST\n")
            for name, target, live, first, until in rows:
                f.write(f"{name}\t{target}\t{live}\t{first}\t{until}\n")

    summary = [f"rows {len(rows)}", f"live-name rows {sum(1 for r in rows if r[2])}",
               f"host-limited rows {sum(1 for r in rows if r[4] != '*')}"]
    summary += [f"{k} {len(v)}" for k, v in sorted(report.items())]
    print("; ".join(summary))
    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            f.write("\n".join(summary) + "\n")
            for kind, lines in sorted(report.items()):
                f.write(f"\n== {kind} ({len(lines)})\n")
                f.write("\n".join(sorted(set(lines))) + "\n")


if __name__ == "__main__":
    main()
