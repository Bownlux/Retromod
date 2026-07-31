#!/usr/bin/env python3
"""Union srg->mojang across the whole Forge SRG era (1.16.5 .. 1.21.8).

For each version: join MCPConfig joined.tsrg (obf<->srg) with Mojang official
client+server mappings (official<->obf) on obf names, within each obf class:
  - FIELDS match on obf field name (unique per class),
  - METHODS match on (obf name + obf descriptor) rebuilt from Mojang's signature
    via the obf class map, disambiguating overloads.
This is the exact join ForgeGradle/SrgUtils perform. Then union all versions;
a given SRG id that maps to DIFFERENT Mojang names across versions is AMBIGUOUS
(a member was renamed/replaced) and is SKIPPED (logged), never guessed. Emits
only entries NOT already in the shipped 1.20.1 table.
"""
import re, os, sys, json, urllib.request, zipfile, io, collections

W = os.environ.get("SRG_WORK", os.path.join(os.path.dirname(__file__), "..", "target", "srg-work"))
os.makedirs(W, exist_ok=True)
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

# SRG-era versions worth harvesting (stable modding targets).
VERSIONS = ["1.16.5", "1.17.1", "1.18.2", "1.19.2", "1.19.4", "1.20.1", "1.20.4", "1.20.6",
            "1.21.1", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8"]

_UA = {"User-Agent": "Mozilla/5.0 (retromod-srg-harvest)"}

def _open(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=_UA))

def fetch(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return dest
    with _open(url) as r, open(dest, "wb") as o:
        o.write(r.read())
    return dest

def mcp_config(ver):
    """Download MCPConfig, return path to config/joined.tsrg."""
    dest = f"{W}/mcp-{ver}.zip"
    fetch(f"https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_config/{ver}/mcp_config-{ver}.zip", dest)
    out = f"{W}/joined-{ver}.tsrg"
    if not os.path.exists(out):
        with zipfile.ZipFile(dest) as z:
            with z.open("config/joined.tsrg") as f, open(out, "wb") as o:
                o.write(f.read())
    return out

def mojang_mappings(ver):
    """Download Mojang client+server ProGuard mappings for ver."""
    paths = []
    manifest = json.load(_open("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
    vurl = next(v["url"] for v in manifest["versions"] if v["id"] == ver)
    vmeta = json.load(_open(vurl))
    for side in ("client_mappings", "server_mappings"):
        if side not in vmeta["downloads"]:
            continue
        dest = f"{W}/{side}-{ver}.txt"
        fetch(vmeta["downloads"][side]["url"], dest)
        paths.append(dest)
    return paths

# ---- ProGuard parse: per obf class -> {obf member -> set(mojang names)} + class map ----
CLS_RE = re.compile(r"^(\S+) -> (\S+):$")
MEM_RE = re.compile(r"^\s+(?:\d+:\d+:)?(\S+) (\w+|<init>|<clinit>)(\(.*\))? -> (\S+)$")

def load_proguard(paths):
    obf2moj_cls = {}                                   # obf internal -> mojang internal
    per_cls_field = collections.defaultdict(lambda: collections.defaultdict(set))
    per_cls_method = collections.defaultdict(lambda: collections.defaultdict(set))  # (obf, argCount?) not enough; keep by name
    per_cls_method_sig = collections.defaultdict(dict)  # obfCls -> {(obfName): {mojType,mojArgs...}} -- store raw for desc build
    raw_methods = collections.defaultdict(list)         # obfCls -> [(mojName, mojRetType, mojArgTypes, obfName)]
    raw_fields = collections.defaultdict(list)          # obfCls -> [(mojName, obfName)]
    cur = None
    for path in paths:
        for line in open(path):
            m = CLS_RE.match(line)
            if m:
                cur = m.group(2).replace(".", "/")
                obf2moj_cls[cur] = m.group(1).replace(".", "/")
                continue
            m = MEM_RE.match(line)
            if m and cur is not None:
                mtype, mname, margs, obf = m.group(1), m.group(2), m.group(3), m.group(4)
                if margs is None:
                    raw_fields[cur].append((mname, obf))
                else:
                    raw_methods[cur].append((mname, mtype, margs, obf))
    return obf2moj_cls, raw_fields, raw_methods

PRIM = {"int": "I", "long": "J", "short": "S", "byte": "B", "char": "C",
        "boolean": "Z", "float": "F", "double": "D", "void": "V"}

def type_to_desc(t, moj2obf_cls):
    arr = ""
    while t.endswith("[]"):
        arr += "["; t = t[:-2]
    if t in PRIM:
        return arr + PRIM[t]
    internal = t.replace(".", "/")
    obf = moj2obf_cls.get(internal, internal)  # descriptors are in OBF terms
    return arr + "L" + obf + ";"

def args_to_desc(margs, moj2obf_cls):
    inner = margs[1:-1]
    if not inner:
        return "()"
    return "(" + "".join(type_to_desc(a.strip(), moj2obf_cls) for a in inner.split(",")) + ")"

def join_version(ver):
    tsrg = mcp_config(ver)
    obf2moj_cls, raw_fields, raw_methods = load_proguard(mojang_mappings(ver))
    moj2obf_cls = {v: k for k, v in obf2moj_cls.items()}

    # obf-side lookups keyed for the tsrg join
    field_lookup = collections.defaultdict(dict)   # obfCls -> {obfFieldName: mojName}
    method_lookup = collections.defaultdict(dict)  # obfCls -> {(obfName, obfArgDesc): mojName}
    for obfCls, flist in raw_fields.items():
        for mojName, obfName in flist:
            field_lookup[obfCls][obfName] = mojName
    for obfCls, mlist in raw_methods.items():
        for mojName, mojType, margs, obfName in mlist:
            argdesc = args_to_desc(margs, moj2obf_cls)
            method_lookup[obfCls][(obfName, argdesc)] = mojName

    # Format detection: tsrg2 has a header line and an extra numeric `id` column, so
    # FIELD = 3 tokens (obf srg id), METHOD = 4 tokens (obf desc srg id). tsrg1 (<=1.16.x)
    # has no header/id: FIELD = 2 tokens (obf srg), METHOD = 3 tokens (obf desc srg).
    first = open(tsrg).readline()
    v2 = first.startswith("tsrg2")

    srg2moj = {}  # ("FIELD"|"METHOD", srg) -> mojName
    cur = None
    for line in open(tsrg):
        line = line.rstrip("\n")
        if not line or line.startswith("tsrg2"):
            continue
        if not line.startswith("\t"):
            cur = line.split(" ")[0]      # obf class
            continue
        if line.startswith("\t\t"):
            continue
        parts = line.strip().split(" ")
        field = None
        method = None
        if v2:
            if len(parts) == 3:           # obf srg id
                field = (parts[0], parts[1])
            elif len(parts) == 4:         # obf desc srg id
                method = (parts[0], parts[1], parts[2])
        else:
            if len(parts) == 2:           # obf srg
                field = (parts[0], parts[1])
            elif len(parts) == 3:         # obf desc srg
                method = (parts[0], parts[1], parts[2])
        if field:
            obf, srg = field
            moj = field_lookup.get(cur, {}).get(obf)
            if moj and srg.startswith(("f_", "field_")):
                srg2moj[("FIELD", srg)] = moj
        elif method:
            obf, desc, srg = method
            argdesc = desc[:desc.rfind(")") + 1]
            moj = method_lookup.get(cur, {}).get((obf, argdesc))
            if moj and srg.startswith(("m_", "func_")):
                if not (moj.startswith("lambda$") or moj.startswith("access$")
                        or moj in ("<init>", "<clinit>")):
                    srg2moj[("METHOD", srg)] = moj
    return srg2moj

def main():
    # existing shipped table
    have = {}
    for line in open(f"{REPO}/src/main/resources/retromod/srg-to-mojang.tsv"):
        if line.startswith("#") or not line.strip():
            continue
        p = line.rstrip("\n").split("\t")
        if len(p) == 3:
            have[(p[0], p[1])] = p[2]

    union = {}
    conflicts = collections.defaultdict(set)
    for ver in VERSIONS:
        print(f"[{ver}] joining...", flush=True)
        try:
            s = join_version(ver)
        except Exception as e:
            print(f"[{ver}] FAILED: {e}", flush=True)
            continue
        for k, moj in s.items():
            if k in union and union[k] != moj:
                conflicts[k].add(union[k]); conflicts[k].add(moj)
            union[k] = moj
        print(f"[{ver}] {len(s)} pairs (running union {len(union)})", flush=True)

    # drop ambiguous ids
    for k in list(conflicts):
        union.pop(k, None)

    new = {k: v for k, v in union.items() if k not in have}
    # cross-check: any NEW entry that conflicts with an EXISTING mapping is suspicious -> drop
    bad_vs_have = {k: (have[k], v) for k, v in union.items() if k in have and have[k] != v}

    print(f"\nunion (post-conflict-drop): {len(union)}")
    print(f"ambiguous ids skipped: {len(conflicts)}")
    print(f"NEW entries (not in shipped table): {len(new)}")
    print(f"  new FIELD: {sum(1 for k in new if k[0]=='FIELD')}  new METHOD: {sum(1 for k in new if k[0]=='METHOD')}")
    print(f"disagree-with-existing (NOT added, logged): {len(bad_vs_have)}")
    for k, (h, u) in list(bad_vs_have.items())[:15]:
        print(f"  DISAGREE {k}: shipped={h} harvest={u}")

    with open(f"{W}/new-srg.tsv", "w") as f:
        for (kind, srg), moj in sorted(new.items()):
            f.write(f"{kind}\t{srg}\t{moj}\n")
    json.dump({"conflicts": {f"{k[0]} {k[1]}": sorted(v) for k, v in conflicts.items()},
               "disagree": {f"{k[0]} {k[1]}": list(v) for k, v in bad_vs_have.items()}},
              open(f"{W}/srg-audit.json", "w"), indent=1)
    print(f"\nwrote {W}/new-srg.tsv")

if __name__ == "__main__":
    main()
