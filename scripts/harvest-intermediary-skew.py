#!/usr/bin/env python3
"""
Resolve dangling Fabric-intermediary member ids to their Mojang names for a given MC version, by
composing that version's INTERMEDIARY tiny (obf <-> intermediary) with Mojang's PROGUARD mappings
(mojang -> obf). Use it to fix "intermediary version skew": the bundled intermediary-to-mojang.tsv
is harvested at the current (unobfuscated) target, but an OLDER mod references the OLDER-era id for
any member Mojang RENAMED since (or whose enclosing class was renamed, which re-mints the member
ids). Those older ids are absent from the harvest and are left dangling.

WHY IT IS SAFE: Fabric intermediary ids are append-only and never reused, so an id absent from the
current map can be given its older-version mapping with zero risk of clobbering a different member.
Always confirm 0 conflicts (an id already in the tsv mapping to a DIFFERENT name would be a red flag)
and that each resolved Mojang name still exists on the target jar (else it is a genuine removal, not
skew, and mapping it does not help).

USAGE:
  1. Download the two sources for the mod's MC version <V>:
       intermediary tiny:  https://maven.fabricmc.net/net/fabricmc/intermediary/<V>/intermediary-<V>-v2.jar
                           (unzip -> mappings/mappings.tiny)
       Mojang proguard:    version_manifest_v2.json -> <V>.json -> downloads.client_mappings.url
  2. Feed the dangling ids (method_NNN / field_NNN, one per line) on stdin:
       python3 harvest-intermediary-skew.py mappings.tiny client.txt < dangling_ids.txt
     Dangling ids come from a corpus link-check: `retromod score <dir> --json out.json`, then collect
     every method_/field_ token still present in the missing-members of the transformed jars.
  3. Cross-check each resolved name against the TARGET jar (javap) before adding: keep only the ones
     whose name still exists there; append `METHOD\t<id>\t<name>` / `FIELD\t<id>\t<name>` to
     src/main/resources/intermediary-to-mojang.tsv (co-located with the other skew fixes).

This is the tool behind the FastColor$ARGB32/ABGR32 -> ARGB color-helper skew fix (1.3.0-snapshot.2).

STALE MODE (--stale): the bundled table was composed from 1.21.4, so ids whose class or member
Mojang renamed between 1.21.4 and 1.21.11 still carry the old name, such as class_1007 ->
PlayerRenderer (AvatarRenderer by 1.21.11) or field_25138 -> location (identifier). A 1.21.11 mod
then reaches a name no 26.x host has. This mode corrects such an entry only when its current value,
after the 26.1 class moves, is absent on EVERY host jar given, and the 1.21.11 name exists on every
one of them. Methods must match by name and full descriptor, fields by name and descriptor. An id
whose occurrences disagree, or whose class is not on every host, is left alone, and so is a class
whose 1.21.11 name another id already holds. It never adds ids.

  python3 harvest-intermediary-skew.py --stale <table.tsv> <class-moves.tsv> \
      <intermediary-1.21.11 tiny (v1 or v2)> <client-1.21.11 proguard> <host.jar>... \
      --keep scripts/intermediary-stale-keep.tsv [--write]

It prints one `KIND\tid\told\tnew\towners` line per correction and, with --write, rewrites the
table in place. Pass the client jars of every 26.x host the build supports, so one value fits them
all. Anonymous classes and compiler-generated members (any name with a `$`) are skipped: javac
numbers and names them, so the same name on a newer jar can belong to different code.

--keep names ids a shim already redirects by their old name, such as the GameRules RULE_*
constants and the pre-1.20.5 MobEffects names. Those redirects also convert the older descriptor a
pre-1.21.11 mod carries, which a corrected name would skip. The file explains how it was built.
"""
import struct, sys, re, zipfile


def parse_tiny(path):
    """intermediary-id -> (kind 'm'/'f', obfClass, obfName, obfDesc)."""
    inter, cur = {}, None
    with open(path) as f:
        next(f)
        for line in f:
            p = line.rstrip("\n").split("\t")
            if p[0] == "c":
                cur = p[1]
            elif len(p) >= 5 and p[0] == "" and p[1] in ("m", "f"):
                inter[p[4]] = (p[1], cur, p[3], p[2])  # id -> (kind, obfClass, obfName, obfDesc)
    return inter


def parse_proguard(path):
    """(obfClass,obfName,obfDesc)->mojName for methods; plus mojClass->obfClass for desc remap."""
    moj2obf, lines = {}, []
    cur = None
    with open(path) as f:
        for line in f:
            if line.startswith("#"):
                continue
            if not line.startswith("    ") and "->" in line:
                l, r = line.split("->")
                cur = (l.strip().replace(".", "/"), r.strip().rstrip(":").replace(".", "/"))
                moj2obf[cur[0]] = cur[1]
            elif line.startswith("    ") and "->" in line and cur:
                body, obfn = line.strip().rsplit("->", 1)
                body = re.sub(r"^\d+:\d+:", "", body.strip())
                lines.append((cur[1], body, obfn.strip()))

    def obf_type(t):
        t = t.strip(); arr = ""
        while t.endswith("[]"):
            arr, t = arr + "[", t[:-2]
        prim = {"void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S",
                "int": "I", "long": "J", "float": "F", "double": "D"}
        if t in prim:
            return arr + prim[t]
        i = t.replace(".", "/")
        return arr + "L" + moj2obf.get(i, i) + ";"

    methods, fields = {}, {}
    for obfc, body, obfn in lines:
        if "(" in body:
            m = re.match(r"(\S+)\s+([^(]+)\((.*)\)", body)
            if not m:
                continue
            ret, name, params = m.groups()
            pl = [p for p in params.split(",") if p.strip()]
            desc = "(" + "".join(obf_type(p) for p in pl) + ")" + obf_type(ret)
            methods[(obfc, obfn, desc)] = name
        else:
            m = re.match(r"\S+\s+(\S+)", body)
            if m:
                fields[(obfc, obfn)] = m.group(1)
    return methods, fields


def main():
    inter = parse_tiny(sys.argv[1])
    methods, fields = parse_proguard(sys.argv[2])
    ids = [l.strip() for l in sys.stdin if l.strip()]
    resolved = 0
    for iid in ids:
        if iid not in inter:
            print(f"{iid}\t<not in this version's intermediary>")
            continue
        kind, obfc, obfn, obfd = inter[iid]
        name = methods.get((obfc, obfn, obfd)) if kind == "m" else fields.get((obfc, obfn))
        tag = "METHOD" if kind == "m" else "FIELD"
        if name:
            print(f"{tag}\t{iid}\t{name}")
            resolved += 1
        else:
            print(f"{iid}\t<obf {obfc}.{obfn}{obfd if kind=='m' else ''} unmatched in proguard>")
    print(f"# resolved {resolved}/{len(ids)}", file=sys.stderr)


def parse_tiny_entries(path):
    """Class map obf -> intermediary, and member rows (kind, obfOwner, obfName, obfDesc, id).

    Reads tiny v1 (CLASS/FIELD/METHOD rows, as the intermediary jar ships for 1.21.11) and v2.
    """
    classes, members, cur = {}, [], None
    with open(path) as f:
        header = next(f).split("\t")
        v2 = header[0] == "tiny"
        for line in f:
            p = line.rstrip("\n").split("\t")
            if v2:
                if p[0] == "c":
                    cur = p[1]
                    classes[p[1]] = p[2]
                elif len(p) >= 5 and p[0] == "" and p[1] in ("m", "f"):
                    members.append(("METHOD" if p[1] == "m" else "FIELD", cur, p[3], p[2], p[4]))
            elif p[0] == "CLASS" and len(p) >= 3:
                classes[p[1]] = p[2]
            elif p[0] in ("FIELD", "METHOD") and len(p) >= 5:
                members.append((p[0], p[1], p[3], p[2], p[4]))
    return classes, members


def parse_proguard_classes(path):
    """obf class -> Mojang internal class name."""
    out = {}
    with open(path) as f:
        for line in f:
            if line.startswith("#") or line.startswith(" ") or "->" not in line:
                continue
            l, r = line.split("->")
            out[r.strip().rstrip(":").replace(".", "/")] = l.strip().replace(".", "/")
    return out


def read_members(data):
    """(fields {name: desc}, methods {(name, desc)}) of one class file."""
    p = 10
    cp_count = struct.unpack_from(">H", data, 8)[0]
    utf8, i = {}, 1
    while i < cp_count:
        tag = data[p]; p += 1
        if tag == 1:
            ln = struct.unpack_from(">H", data, p)[0]; p += 2
            utf8[i] = data[p:p + ln].decode("utf-8", "replace"); p += ln
        elif tag in (7, 8, 16, 19, 20): p += 2
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18): p += 4
        elif tag in (5, 6): p += 8; i += 1
        elif tag == 15: p += 3
        else: raise ValueError(f"bad constant pool tag {tag}")
        i += 1
    p += 6
    interfaces = struct.unpack_from(">H", data, p)[0]; p += 2 + 2 * interfaces
    fields, methods = {}, set()
    for kind in ("field", "method"):
        count = struct.unpack_from(">H", data, p)[0]; p += 2
        for _ in range(count):
            _access, name, desc = struct.unpack_from(">HHH", data, p); p += 6
            if kind == "field":
                fields[utf8[name]] = utf8[desc]
            else:
                methods.add((utf8[name], utf8[desc]))
            attributes = struct.unpack_from(">H", data, p)[0]; p += 2
            for _a in range(attributes):
                p += 6 + struct.unpack_from(">I", data, p + 2)[0]
    return fields, methods


def read_host(jar):
    """Internal class name -> (fields, methods) for Minecraft and Mojang classes."""
    out = {}
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if n.endswith(".class") and n.startswith(("net/minecraft/", "com/mojang/")):
                out[n[:-6]] = read_members(z.read(n))
    return out


DESC_CLASS = re.compile(r"L([^;]+);")


def stale_corrections(table_path, moves_path, tiny_path, proguard_path, host_jars):
    classes, members = parse_tiny_entries(tiny_path)
    inter_to_obf = {v: k for k, v in classes.items()}
    obf_to_1211 = parse_proguard_classes(proguard_path)
    methods_1211, fields_1211 = parse_proguard(proguard_path)
    moves = {}
    for line in open(moves_path):
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 2 and not line.startswith("#"):
            moves[parts[0]] = parts[1]
    table = {"CLASS": {}, "FIELD": {}, "METHOD": {}}
    for line in open(table_path):
        parts = line.rstrip("\n").split("\t")
        if len(parts) == 3 and parts[0] in table:
            table[parts[0]][parts[1]] = parts[2]
    hosts = [read_host(jar) for jar in host_jars]
    on_all = set.intersection(*(set(h) for h in hosts))
    on_any = set.union(*(set(h) for h in hosts))

    corrections = []
    # A name another id already maps to may belong to a different class that Mojang merged or
    # renamed, the case IntermediaryDestinationCollisionTest guards, so it is never reused here.
    claimed = set(table["CLASS"].values())
    for inter, value in table["CLASS"].items():
        if moves.get(value, value) in on_any or is_anonymous(inter):
            continue
        renamed = obf_to_1211.get(inter_to_obf.get(inter))
        if renamed and renamed != value and renamed in on_all and renamed not in claimed:
            corrections.append(("CLASS", inter, value, renamed, ""))
    class_fix = {c[1]: c[3] for c in corrections}

    def host_class(inter):
        value = class_fix.get(inter, table["CLASS"].get(inter))
        return moves.get(value, value) if value else None

    def host_desc(obf_desc):
        def swap(m):
            inter = classes.get(m.group(1))
            if inter is None:
                return m.group(0)
            name = host_class(inter)
            return "L" + name + ";" if name else m.group(0)
        return DESC_CLASS.sub(swap, obf_desc)

    # One verdict per id. Overrides share an id, so every occurrence has to agree.
    verdicts = {}
    for kind, obf_owner, obf_name, obf_desc, inter in members:
        current = table[kind].get(inter)
        owner = host_class(classes.get(obf_owner, ""))
        if current is None or owner is None or owner not in on_all or "$" in current:
            verdicts.setdefault(inter, set()).add(None)
            continue
        if kind == "FIELD":
            renamed = fields_1211.get((obf_owner, obf_name))
            desc = host_desc(obf_desc)
            had_current = any(current in h[owner][0] for h in hosts)
            has_renamed = renamed and all(h[owner][0].get(renamed) == desc for h in hosts)
        else:
            renamed = methods_1211.get((obf_owner, obf_name, obf_desc))
            desc = host_desc(obf_desc)
            had_current = any(any(n == current for n, _ in h[owner][1]) for h in hosts)
            has_renamed = renamed and all((renamed, desc) in h[owner][1] for h in hosts)
        if renamed == current or "$" in (renamed or ""):
            verdicts.setdefault(inter, set()).add(None)
        elif not had_current and has_renamed:
            verdicts.setdefault(inter, set()).add((kind, current, renamed, owner))
        else:
            verdicts.setdefault(inter, set()).add(None)
    for inter, found in sorted(verdicts.items()):
        # Overrides of one id in several classes must agree on the rename itself.
        renames = {(f[0], f[1], f[2]) for f in found if f is not None}
        if None not in found and len(renames) == 1:
            kind, current, renamed = next(iter(renames))
            owners = ",".join(sorted(f[3] for f in found))
            corrections.append((kind, inter, current, renamed, owners))
    return corrections


def is_anonymous(inter):
    """Anonymous and local classes are numbered by javac, so the same number can name another class."""
    return any(part[:1].isdigit() for part in inter.split("$")[1:])


def write_corrections(table_path, corrections):
    fix = {(c[0], c[1]): c[3] for c in corrections}
    lines = []
    for line in open(table_path):
        parts = line.rstrip("\n").split("\t")
        if len(parts) == 3 and (parts[0], parts[1]) in fix:
            line = f"{parts[0]}\t{parts[1]}\t{fix[(parts[0], parts[1])]}\n"
        lines.append(line)
    with open(table_path, "w") as out:
        out.writelines(lines)


def read_keep(path):
    """(kind, id) pairs to leave alone, from `KIND\tid\told` lines."""
    keep = set()
    for line in open(path):
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 2 and not line.startswith("#"):
            keep.add((parts[0], parts[1]))
    return keep


def stale_main(args):
    write = "--write" in args
    args = [a for a in args if a != "--write"]
    keep = set()
    if "--keep" in args:
        at = args.index("--keep")
        keep = read_keep(args[at + 1])
        del args[at:at + 2]
    if len(args) < 5:
        sys.exit("usage: --stale <table.tsv> <class-moves.tsv> <tiny> <proguard> <host.jar>..."
                 " [--keep <file>] [--write]")
    corrections = [c for c in stale_corrections(args[0], args[1], args[2], args[3], args[4:])
                   if (c[0], c[1]) not in keep]
    for kind, inter, current, renamed, owners in corrections:
        print(f"{kind}\t{inter}\t{current}\t{renamed}\t{owners}".rstrip("\t"))
    counts = {k: sum(1 for c in corrections if c[0] == k) for k in ("CLASS", "FIELD", "METHOD")}
    print(f"# stale entries: {counts}", file=sys.stderr)
    if write:
        write_corrections(args[0], corrections)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--stale":
        stale_main(sys.argv[2:])
    else:
        main()
