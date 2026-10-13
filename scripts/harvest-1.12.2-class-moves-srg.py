#!/usr/bin/env python3
"""Harvest 1.12.2 (MCP) -> Mojang class moves by SRG member continuity.

MCP numbered every field and method (field_70165_t, func_70097_a), and MCPConfig kept those
ids from 1.12.2 through 1.16.5 for members that survived. So a 1.12.2 class and the 1.16.5
class that declares the same SRG ids are the same class, whatever either was renamed to.
Mojang's official 1.16.5 mappings then give that class's Mojang name. Each id is weighted by
how many 1.16.5 classes declare it, so an override inherited by many subclasses counts for
little and a field only one class has counts fully. At least one id must belong to the old
class alone in 1.12.2 and to the matched class alone in 1.16.5, or a class that kept only
overrides would match its parent. Anonymous classes are skipped.

The 1.16.5 shim reuses this table for 1.16.5 mods, so a 1.12.2 name that 1.16.5's MCP names gave
to a different class (ItemRenderer) is left out.

A class is emitted only when the best match clearly beats the runner-up, the 1.12.2 name is
not already in the curated table, and the Mojang name exists in the target class set. Classes
that lost every numbered member, such as Forge-only or rewritten ones, are not matched.

Usage: harvest-1.12.2-class-moves-srg.py <joined-1.12.2.srg> <joined-1.16.5.tsrg>
       <mojang-1.16.5-client.txt> <target: mojang client.txt or client jar>
       <curated-tsv> <out-tsv> [renames-tsv]

renames-tsv maps a Mojang 1.16.5 name to the name it has on the target when Mojang renamed or
moved it later (the 26.1 class-move table, for example); it is optional.
"""
import collections
import re
import sys
import zipfile

SRG_1122, TSRG_1165, MOJANG_1165, TARGET, CURATED, OUT = sys.argv[1:7]
RENAMES = sys.argv[7] if len(sys.argv) > 7 else None
SRG_ID = re.compile(r"(?:func|field)_(\d+)_")


def srg_number(name):
    match = SRG_ID.match(name)
    return match.group(1) if match else None


# 1.12.2: MCP class -> set of SRG member numbers it declares
old_members = collections.defaultdict(set)
with open(SRG_1122, encoding="utf-8") as f:
    for line in f:
        if line.startswith(("FD:", "MD:")):
            parts = line.split()
            named = parts[2] if line.startswith("FD:") else parts[3]
            owner, _, member = named.rpartition("/")
            number = srg_number(member)
            if number:
                old_members[owner].add(number)

# 1.12.2: SRG number -> how many classes declare it (an override is declared by each class)
old_declarers = collections.Counter(n for members in old_members.values() for n in members)

# 1.16.5: SRG number -> obfuscated classes that declare it, and MCP class name -> obfuscated
declared_by = collections.defaultdict(set)
mcp_1165 = {}
with open(TSRG_1165, encoding="utf-8") as f:
    current = None
    for line in f:
        if not line.startswith("\t"):
            parts = line.split()
            current = parts[0]
            if len(parts) > 1:
                mcp_1165[parts[1]] = current
            continue
        number = srg_number(line.split()[-1])
        if number and current:
            declared_by[number].add(current)

# 1.16.5: obfuscated class -> Mojang name
mojang_1165 = {}
with open(MOJANG_1165, encoding="utf-8") as f:
    for line in f:
        if line.startswith(("#", " ")) or " -> " not in line:
            continue
        named, obf = line.rstrip().rstrip(":").split(" -> ")
        mojang_1165[obf] = named.replace(".", "/")

# target class set
if TARGET.endswith(".jar"):
    with zipfile.ZipFile(TARGET) as jar:
        target = {n[:-6] for n in jar.namelist() if n.endswith(".class")}
else:
    target = set()
    with open(TARGET, encoding="utf-8") as f:
        for line in f:
            if not line.startswith(("#", " ")) and " -> " in line:
                target.add(line.split(" -> ")[0].replace(".", "/"))

renames = {}
if RENAMES:
    with open(RENAMES, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or "\t" not in line:
                continue
            old, new = line.rstrip("\n").split("\t")[:2]
            renames[old.strip()] = new.strip()

curated = set()
with open(CURATED, encoding="utf-8") as f:
    for line in f:
        if not line.startswith("#") and "\t" in line:
            curated.add(line.split("\t")[0].strip())

emitted = []
for old, numbers in sorted(old_members.items()):
    # Anonymous classes are numbered per compile, so their numbers say nothing across versions.
    if old in curated or re.search(r"\$\d", old):
        continue
    score = collections.Counter()
    for number in numbers:
        owners = declared_by.get(number)
        if owners:
            for owner in owners:
                score[owner] += 1.0 / len(owners)
    if not score:
        continue
    ranked = score.most_common(2)
    best, best_score = ranked[0]
    runner_up = ranked[1][1] if len(ranked) > 1 else 0.0
    if best_score < 1.0 or best_score < 2 * runner_up:
        continue
    # A class whose surviving ids are all overrides would match its parent, so at least one id
    # must be the old class's own in 1.12.2 and the matched class's own in 1.16.5.
    if not any(old_declarers[number] == 1 and declared_by.get(number) == {best} for number in numbers):
        continue
    name = mojang_1165.get(best)
    if not name:
        continue
    name = renames.get(name, name)
    if name == old or name not in target:
        continue
    reused = mcp_1165.get(old)
    if reused and renames.get(mojang_1165.get(reused), mojang_1165.get(reused)) != name:
        continue
    emitted.append((old, name, best_score))

with open(OUT, "w", encoding="utf-8") as f:
    f.write("# 1.12.2 (MCP) -> Mojang class moves matched by SRG member continuity\n")
    f.write("# (scripts/harvest-1.12.2-class-moves-srg.py). Every target exists on the host.\n")
    for old, name, _ in emitted:
        f.write(f"{old}\t{name}\n")
print(f"{len(emitted)} classes matched and emitted to {OUT}")
