#!/usr/bin/env python3
"""Find mixin methods that no longer exist in a target Minecraft jar.

The class-level cross-join cannot spot a renamed or removed method when its class still
exists. This script resolves each scanned target, follows known class moves and inherited
methods, and ranks the missing targets by the number of affected jars.

The scan and target jar must both use Mojang names. Map a Fabric scan before running it.

Usage: python3 mixin-method-breaks.py <scan.json> <26.2-client.jar> [--class-moves <tsv>]
"""
import collections
import json
import struct
import sys
import zipfile

def arg(flag, default=None):
    return sys.argv[sys.argv.index(flag) + 1] if flag in sys.argv else default

SCAN = sys.argv[1]
JAR = sys.argv[2]
MOVES_TSV = arg("--class-moves",
                __file__.rsplit("/", 1)[0] + "/../src/main/resources/mojang-class-moves-26.1.tsv")

def parse_class(data):
    p = 10
    cp = struct.unpack_from(">H", data, 8)[0]
    utf8 = {}; klass = {}; i = 1
    while i < cp:
        tag = data[p]; p += 1
        if tag == 1:
            ln = struct.unpack_from(">H", data, p)[0]; p += 2
            utf8[i] = data[p:p+ln].decode("utf-8", "replace"); p += ln
        elif tag in (7, 8, 16, 19, 20):
            if tag == 7: klass[i] = struct.unpack_from(">H", data, p)[0]
            p += 2
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18): p += 4
        elif tag in (5, 6): p += 8; i += 1
        elif tag == 15: p += 3
        else: raise ValueError(tag)
        i += 1
    p += 2
    this_i = struct.unpack_from(">H", data, p)[0]; p += 2
    this = utf8[klass[this_i]]
    sup_i = struct.unpack_from(">H", data, p)[0]; p += 2
    sup = utf8[klass[sup_i]] if sup_i != 0 else None
    ic = struct.unpack_from(">H", data, p)[0]; p += 2 + 2*ic
    fc = struct.unpack_from(">H", data, p)[0]; p += 2   # fields
    for _ in range(fc):
        p += 6
        ac = struct.unpack_from(">H", data, p)[0]; p += 2
        for _a in range(ac): p += 6 + struct.unpack_from(">I", data, p+2)[0]
    mc = struct.unpack_from(">H", data, p)[0]; p += 2   # methods
    methods = set()
    for _ in range(mc):
        _acc, ni, _di = struct.unpack_from(">HHH", data, p); p += 6
        methods.add(utf8[ni])
        ac = struct.unpack_from(">H", data, p)[0]; p += 2
        for _a in range(ac): p += 6 + struct.unpack_from(">I", data, p+2)[0]
    return this, sup, methods

def load_jar(jar):
    supers, methods = {}, {}
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if not n.endswith(".class"): continue
            try:
                this, sup, ms = parse_class(z.read(n))
            except Exception:
                continue
            supers[this] = sup
            methods[this] = ms
    return supers, methods

def load_moves(path):
    mv = {}
    try:
        for line in open(path):
            if line.startswith("#") or not line.strip(): continue
            p = line.rstrip("\n").split("\t")
            if len(p) >= 2: mv[p[0]] = p[1]
    except FileNotFoundError:
        pass
    return mv

def resolve_class(cls, methods, moves):
    """Return the 26.2 internal name for a 1.21.x target class, or None if gone."""
    if cls in methods: return cls
    seen = set()
    cur = cls
    while cur in moves and cur not in seen:
        seen.add(cur); cur = moves[cur]
        if cur in methods: return cur
    return None

def has_method(cls262, name, methods, supers):
    cur = cls262
    for _ in range(64):
        if cur is None: return False
        if name in methods.get(cur, ()): return True
        if cur == "java/lang/Object": return False
        cur = supers.get(cur)
    return False

def main():
    scan = json.load(open(SCAN))
    supers, methods = load_jar(JAR)
    moves = load_moves(MOVES_TSV)

    agg = collections.defaultdict(lambda: {"jars": set(), "injectors": collections.Counter(), "status": None})
    for r in scan["records"]:
        for tc in r.get("targetClasses") or []:
            if not (tc.startswith("net/minecraft/") or tc.startswith("com/mojang/")):
                continue
            for selector in r.get("targetSelectors") or []:
                method_name = selector.split("(")[0].split(";")[-1].lstrip("L")
                if not method_name or method_name in ("<init>", "<clinit>"):
                    continue
                key = (tc, method_name)
                cls262 = resolve_class(tc, methods, moves)
                if cls262 is None:
                    st = "CLASS_GONE"
                elif has_method(cls262, method_name, methods, supers):
                    st = "OK"
                else:
                    st = "METHOD_GONE"
                result = agg[key]
                result["jars"].add(r["jar"])
                result["injectors"][r.get("injector")] += 1
                result["status"] = st
    broken = [(k, v) for k, v in agg.items() if v["status"] in ("METHOD_GONE", "CLASS_GONE")]
    broken.sort(key=lambda kv: (-len(kv[1]["jars"]), kv[0]))
    print(f"Targets checked: {len(agg)}")
    print(f"Missing classes or methods: {len(broken)}\n")
    print(f"{'freq':>4}  {'status':<11} {'injectors':<24} targetClass::method")
    for (tc, nm), v in broken:
        inj = ",".join(f"{k}:{c}" for k, c in v["injectors"].most_common(3))
        print(f"{len(v['jars']):>4}  {v['status']:<11} {inj:<24} {tc}::{nm}")

if __name__ == "__main__":
    main()
