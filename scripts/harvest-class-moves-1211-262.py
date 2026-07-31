#!/usr/bin/env python3
"""Find likely vanilla class moves between Minecraft 1.21.1 and 26.2.

The script compares 1.21.1 Mojang mappings with the unobfuscated 26.2 client jar.
A candidate must disappear from its old path, keep the same simple name at one new path,
and share members with the new class. Anonymous classes and existing redirects are skipped.

Renames are deliberately out of scope because matching them requires semantic review.
Results are written to `target/srg-work/class-moves-1211-262.txt`.
"""
import os, re, json, struct, zipfile, urllib.request, collections

W = os.environ.get("SRG_WORK", os.path.join(os.path.dirname(__file__), "..", "target", "srg-work"))
os.makedirs(W, exist_ok=True)
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
_UA = {"User-Agent": "Mozilla/5.0 (retromod-classmove-harvest)"}
def op(u): return urllib.request.urlopen(urllib.request.Request(u, headers=_UA))
def fetch(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return dest
    with op(url) as r, open(dest, "wb") as o:
        o.write(r.read())
    return dest

def dl_1211_proguard():
    man = json.load(op("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
    meta = json.load(op(next(v["url"] for v in man["versions"] if v["id"] == "1.21.1")))
    return [fetch(meta["downloads"][s]["url"], f"{W}/{s}-1.21.1.txt")
            for s in ("client_mappings", "server_mappings")]

CLS_RE = re.compile(r"^(\S+) -> (\S+):$")
MEM_RE = re.compile(r"^\s+(?:\d+:\d+:)?\S+ (\w+|<init>)(\(.*\))? -> \S+$")
def classes_1211(paths):
    """1.21.1 Mojang internal class name -> set(member Mojang names); vanilla only."""
    out = collections.defaultdict(set)
    for p in paths:
        cur = None
        for line in open(p):
            m = CLS_RE.match(line)
            if m:
                moj = m.group(1).replace(".", "/")
                cur = moj if (moj.startswith("net/minecraft/") or moj.startswith("com/mojang/blaze3d/")) else None
                if cur is not None:
                    out.setdefault(cur, set())
                continue
            m = MEM_RE.match(line)
            if m and cur is not None and m.group(1) not in ("<init>", "<clinit>"):
                out[cur].add(m.group(1))
    return out

# ---- minimal .class member scanner (name-only) for the 26.2 jar ----
def _member_names(data):
    p = 10
    cp_count = struct.unpack_from(">H", data, 8)[0]
    utf8 = {}; i = 1
    while i < cp_count:
        tag = data[p]; p += 1
        if tag == 1:
            ln = struct.unpack_from(">H", data, p)[0]; p += 2
            utf8[i] = data[p:p+ln].decode("utf-8", "replace"); p += ln
        elif tag in (7, 8, 16, 19, 20): p += 2
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18): p += 4
        elif tag in (5, 6): p += 8; i += 1
        elif tag == 15: p += 3
        else: raise ValueError(f"bad cp tag {tag}")
        i += 1
    p += 6                                             # access, this, super
    ic = struct.unpack_from(">H", data, p)[0]; p += 2 + 2*ic
    names = set()
    for _ in range(2):                                 # fields then methods
        cnt = struct.unpack_from(">H", data, p)[0]; p += 2
        for _m in range(cnt):
            _acc, ni, _di = struct.unpack_from(">HHH", data, p); p += 6
            nm = utf8[ni]
            if nm not in ("<init>", "<clinit>"): names.add(nm)
            ac = struct.unpack_from(">H", data, p)[0]; p += 2
            for _a in range(ac):
                p += 6 + struct.unpack_from(">I", data, p + 2)[0]
    return names

def classes_262(jar):
    """26.2 internal class name -> set(member names); vanilla only."""
    out = {}
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if n.endswith(".class") and (n.startswith("net/minecraft/") or n.startswith("com/mojang/blaze3d/")):
                try:
                    out[n[:-6]] = _member_names(z.read(n))
                except Exception:
                    out[n[:-6]] = set()
    return out

def shipped_redirects():
    """Return source names already covered by Java or resource redirect tables."""
    have = set()
    for f in ("src/main/java/com/retromod/shim/common/Common_1_21_11_to_26_1_ClassMoves.java",
              "src/main/java/com/retromod/shim/common/Mc26_1To26_2CoreMoves.java"):
        path = os.path.join(REPO, f)
        if not os.path.exists(path):
            continue
        src = open(path).read()
        for m in re.finditer(r'register(?:ClassRedirect|SuperclassRebase)\(\s*"([^"]+)"', src):
            have.add(m.group(1))
    tsv = os.path.join(REPO, "src/main/resources/mojang-class-moves-26.1.tsv")
    if os.path.exists(tsv):
        for line in open(tsv):
            if line.startswith("#") or not line.strip():
                continue
            have.add(line.split("\t")[0].strip())
    return have

def simple(name):
    # last path + full inner chain simple name: net/a/b/Outer$Inner -> Outer$Inner
    return name.rsplit("/", 1)[-1]

def is_anon_inner(name):
    # any $<digit> segment => compiler-generated anonymous/local class
    return bool(re.search(r"\$\d", name))

def main():
    pg = dl_1211_proguard()
    jar = fetch(json.load(op(next(v["url"] for v in json.load(
        op("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))["versions"]
        if v["id"] == "26.2")))["downloads"]["client"]["url"], f"{W}/client-26.2.jar")
    old = classes_1211(pg)          # class -> member names
    new = classes_262(jar)          # class -> member names
    have = shipped_redirects()

    by_simple_new = collections.defaultdict(list)
    for c in new:
        by_simple_new[simple(c)].append(c)

    def overlap_ok(src, dst):
        # A shared simple name is not enough to prove that two classes are related.
        a, b = old.get(src, set()), new.get(dst, set())
        if not a:
            return True    # source has no named members (marker/tag class): name+package move is enough
        shared = len(a & b)
        # Small marker-like classes need a lower absolute threshold.
        return shared >= max(3, 0.5 * len(a)) or (len(a) <= 4 and shared >= 1)

    moves, ambiguous, removed, rejected = [], [], [], []
    for c in sorted(old):
        if c in new or c in have or is_anon_inner(c):
            continue
        cands = by_simple_new.get(simple(c), [])
        if len(cands) == 1:
            if overlap_ok(c, cands[0]):
                moves.append((c, cands[0]))
            else:
                rejected.append((c, cands[0]))
        elif len(cands) == 0:
            removed.append(c)
        else:
            # Keep an ambiguous simple name only when member overlap leaves one candidate.
            passing = [d for d in cands if overlap_ok(c, d)]
            if len(passing) == 1:
                moves.append((c, passing[0]))
            else:
                ambiguous.append((c, cands))

    print(f"1.21.1 vanilla classes: {len(old)}   26.2: {len(new)}")
    print(f"Likely package moves: {len(moves)}")
    print(f"Rejected same-name matches: {len(rejected)}")
    for c, d in rejected[:20]:
        print(f"  rejected {c} -> {d}")
    print(f"Ambiguous matches skipped: {len(ambiguous)}")
    print(f"Removed classes skipped: {len(removed)}\n")

    with open(f"{W}/class-moves-1211-262.txt", "w") as f:
        for src, dst in moves:
            f.write(f'        transformer.registerClassRedirect(\n            "{src}",\n            "{dst}");\n')
    # also a compact tsv for eyeballing
    with open(f"{W}/class-moves-1211-262.tsv", "w") as f:
        for src, dst in moves:
            f.write(f"{src}\t{dst}\n")
    json.dump({"ambiguous": {c: v for c, v in ambiguous}, "removed": removed},
              open(f"{W}/class-moves-audit.json", "w"), indent=1)

    print("=== LIKELY PACKAGE MOVES ===")
    for src, dst in moves:
        print(f"  {src}\n    -> {dst}")
    print(f"\nwrote {W}/class-moves-1211-262.txt (and .tsv)")

if __name__ == "__main__":
    main()
