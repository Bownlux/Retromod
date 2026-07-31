#!/usr/bin/env python3
"""Find methods that gained a leading ServerLevel parameter after Minecraft 1.21.1.

The comparison uses official 1.21.1 Mojang mappings and descriptors read from the
unobfuscated 26.2 client jar. A method qualifies when 26.2 adds `ServerLevel` before
the old parameters and removes the old descriptor.

The output groups results by method name and includes the old first-parameter types
used as safety guards by `MixinHandlerResignature`. Existing registrations and
old zero-argument methods are omitted.
"""
import os, io, json, struct, zipfile, urllib.request, re, collections

W = os.environ.get("SRG_WORK", os.path.join(os.path.dirname(__file__), "..", "target", "srg-work"))
os.makedirs(W, exist_ok=True)
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SL = "Lnet/minecraft/server/level/ServerLevel;"
_UA = {"User-Agent": "Mozilla/5.0 (retromod-serverlevel-harvest)"}

def op(u): return urllib.request.urlopen(urllib.request.Request(u, headers=_UA))

def fetch(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return dest
    with op(url) as r, open(dest, "wb") as o:
        o.write(r.read())
    return dest

def dl_meta(ver):
    man = json.load(op("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
    return json.load(op(next(v["url"] for v in man["versions"] if v["id"] == ver)))

# ---- .class parser: (thisClass, methodName, methodDesc, accessFlags) ----
ACC_SYNTHETIC, ACC_BRIDGE = 0x1000, 0x0040
def parse_class(data):
    # constant pool -> resolve Utf8 by index; find this_class name; list method name/desc.
    p = 10                                             # skip magic(4) minor(2) major(2) -> cp_count at 8..10
    cp_count = struct.unpack_from(">H", data, 8)[0]
    utf8 = {}; klass = {}                              # index -> str ; classIdx -> nameIndex
    i = 1
    while i < cp_count:
        tag = data[p]; p += 1
        if tag == 1:                                   # Utf8
            ln = struct.unpack_from(">H", data, p)[0]; p += 2
            utf8[i] = data[p:p+ln].decode("utf-8", "replace"); p += ln
        elif tag in (7, 8, 16, 19, 20):                # Class/String/MethodType/Module/Package: u2
            if tag == 7: klass[i] = struct.unpack_from(">H", data, p)[0]
            p += 2
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):     # int/float/ref/nameandtype/dynamic: u4
            p += 4
        elif tag in (5, 6):                            # long/double: u8, takes TWO cp slots
            p += 8; i += 1
        elif tag == 15:                                # MethodHandle: u1 u2
            p += 3
        else:
            raise ValueError(f"bad cp tag {tag}")
        i += 1
    p += 2                                             # access_flags
    this_idx = struct.unpack_from(">H", data, p)[0]; p += 2
    this_name = utf8[klass[this_idx]]
    p += 2                                             # super_class
    ic = struct.unpack_from(">H", data, p)[0]; p += 2 + 2*ic   # interfaces
    def skip_members():
        cnt = struct.unpack_from(">H", data, p0[0])[0]; p0[0] += 2
        out = []
        for _ in range(cnt):
            acc, ni, di = struct.unpack_from(">HHH", data, p0[0]); p0[0] += 6
            ac = struct.unpack_from(">H", data, p0[0])[0]; p0[0] += 2   # attributes_count
            for _a in range(ac):
                alen = struct.unpack_from(">I", data, p0[0] + 2)[0]
                p0[0] += 6 + alen
            out.append((acc, utf8[ni], utf8[di]))
        return out
    p0 = [p]
    skip_members()                                     # fields
    methods = skip_members()                           # methods
    return this_name, methods

def scan_jar(jar):
    """mojClass -> {methodName -> set(desc)} for net/minecraft methods (no synthetic/bridge)."""
    by = collections.defaultdict(lambda: collections.defaultdict(set))
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if not n.endswith(".class") or not n.startswith("net/minecraft/"):
                continue
            try:
                cls, methods = parse_class(z.read(n))
            except Exception:
                continue
            for acc, name, desc in methods:
                if acc & (ACC_SYNTHETIC | ACC_BRIDGE): continue
                if name in ("<init>", "<clinit>") or name.startswith("lambda$"): continue
                by[cls][name].add(desc)
    return by

# ---- 1.21.1 official ProGuard -> mojClass -> {methodName -> set(mojDesc)} ----
CLS_RE = re.compile(r"^(\S+) -> (\S+):$")
MEM_RE = re.compile(r"^\s+(?:\d+:\d+:)?(\S+) (\w+|<init>|<clinit>)(\(.*\)) -> (\S+)$")
PRIM = {"int":"I","long":"J","short":"S","byte":"B","char":"C","boolean":"Z","float":"F","double":"D","void":"V"}
def tdesc(t):
    arr = ""
    while t.endswith("[]"): arr += "["; t = t[:-2]
    if t in PRIM: return arr + PRIM[t]
    return arr + "L" + t.replace(".", "/") + ";"
def argdesc(margs):
    inner = margs[1:-1]
    return "(" + "".join(tdesc(a.strip()) for a in inner.split(",")) + ")" if inner else "()"
def scan_proguard(paths):
    by = collections.defaultdict(lambda: collections.defaultdict(set))
    cur = None
    for path in paths:
        for line in open(path):
            m = CLS_RE.match(line)
            if m:
                cur = m.group(1).replace(".", "/")     # MOJANG (official) internal name is the LEFT side
                continue
            m = MEM_RE.match(line)
            if m and cur is not None:
                mtype, mname, margs, _obf = m.groups()
                if mname in ("<init>", "<clinit>") or mname.startswith("lambda$"): continue
                by[cur][mname].add(argdesc(margs) + tdesc(mtype))
    return by

def existing_names():
    """Bare method names already registered in SIGNATURE_CHANGES (skip them)."""
    src = open(f"{REPO}/src/main/java/com/retromod/mixin/MixinHandlerResignature.java").read()
    names = set(re.findall(r'reg\("([^"]+)"', src))
    names |= set(re.findall(r'SIGNATURE_CHANGES\.put\("([^"]+)"', src))
    return names

def main():
    m262 = dl_meta("26.2"); m2111 = dl_meta("1.21.1")
    jar = fetch(m262["downloads"]["client"]["url"], f"{W}/client-26.2.jar")
    pg = [fetch(m2111["downloads"][s]["url"], f"{W}/{s}-1.21.1.txt")
          for s in ("client_mappings", "server_mappings")]
    print("scanning 26.2 jar...", flush=True)
    new = scan_jar(jar)
    print("scanning 1.21.1 mappings...", flush=True)
    old = scan_proguard(pg)
    have = existing_names()

    # name -> set(old first-param descriptor)  (guard); "" marks a 0-arg old sig (excluded later)
    guards = collections.defaultdict(set)
    hits = collections.defaultdict(set)   # name -> set(owner) for reporting
    for cls, nm_map in new.items():
        oldcls = old.get(cls)
        if not oldcls: continue
        for name, newdescs in nm_map.items():
            olddescs = oldcls.get(name)
            if not olddescs: continue
            for nd in newdescs:
                if not nd.startswith("(" + SL): continue
                candidate_old = "(" + nd[1 + len(SL):]        # strip the leading ServerLevel param
                if candidate_old in olddescs and candidate_old not in newdescs:
                    args = candidate_old[1:candidate_old.rfind(")")]
                    if not args:                              # 0-arg old sig: no re-signature needed
                        continue
                    # first param type
                    fp = first_param(args)
                    if fp:
                        guards[name].add(fp); hits[name].add(cls)

    incremental = {n: g for n, g in guards.items() if n not in have}
    print(f"\nMethods with a new ServerLevel parameter: {len(guards)}")
    print(f"Already registered: {len(guards) - len(incremental)}")
    print(f"New candidates: {len(incremental)}")

    out = io.StringIO()
    for name in sorted(incremental):
        gs = sorted(incremental[name])
        owners = sorted(hits[name])
        guard_args = ", ".join('"' + g + '"' for g in gs)
        out.write(f'        reg("{name}", sl, {guard_args});')
        out.write(f'   // {", ".join(o.split("/")[-1] for o in owners[:4])}')
        if len(owners) > 4: out.write(f" +{len(owners)-4}")
        out.write("\n")
    open(f"{W}/serverlevel-prepend.txt", "w").write(out.getvalue())
    print(f"\nwrote {W}/serverlevel-prepend.txt\n")
    print(out.getvalue())

def first_param(args):
    # args is the inner descriptor string; return the first param descriptor or None.
    i = 0
    while i < len(args) and args[i] == "[":
        i += 1
    if i >= len(args): return None
    c = args[i]
    if c == "L":
        return args[:args.index(";", i) + 1]
    if c in "IJSBCZFD":
        return args[:i+1]   # primitive first param (with any array prefix)
    return None

if __name__ == "__main__":
    main()
