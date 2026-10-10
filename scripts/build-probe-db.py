#!/usr/bin/env python3
"""Distill Retromod's mapping / rename / class-move tables into a compact JSON the
client-side Probe page (docs/probe.html) reads to answer "will this jar likely transform?".

Everything the browser needs is class-level survival + moves + a supported-version range;
no JVM at query time. Emits docs/assets/probe-db.json. Re-run whenever the mapping tables or
the known-incompatible list change (cheap; sibling to compute-self-hash / the harvest scripts).

Usage:  python3 scripts/build-probe-db.py
"""
import json
import os
import re
import datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src/main/resources")
OUT = os.path.join(ROOT, "docs/assets/probe-db.json")


def read_json(rel):
    with open(os.path.join(RES, rel), encoding="utf-8") as f:
        return json.load(f)


def read_tsv_pairs(rel):
    """OLD<TAB>NEW rows, skipping # comments and blanks."""
    out = {}
    path = os.path.join(RES, rel)
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) >= 2 and parts[0] and parts[1]:
                out[parts[0]] = parts[1]
    return out


def retromod_version():
    # Single source of truth: RetromodVersion.RETROMOD_VERSION
    p = os.path.join(ROOT, "src/main/java/com/retromod/core/RetromodVersion.java")
    with open(p, encoding="utf-8") as f:
        m = re.search(r'RETROMOD_VERSION\s*=\s*"([^"]+)"', f.read())
    return m.group(1) if m else "unknown"


# ---- rename tables Retromod handles (referencing the OLD name is fine: Retromod remaps it) ----
renamed = {}  # old internal name -> new internal name

lar = read_json("retromod/loader-api-renames.json")
removed_bridged = set()
for loader in ("fabric", "neoforge", "forge"):
    node = lar.get(loader, {})
    renamed.update(node.get("renamed_classes", {}) or {})
    for rc in (node.get("removed_classes", []) or []):
        removed_bridged.add(rc)

for rel in ("retromod/forge-event-renames.json", "retromod/forge-fml-renames.json"):
    path = os.path.join(RES, rel)
    if os.path.exists(path):
        renamed.update(read_json(rel))

# MC internal package reorganizations Retromod remaps automatically.
class_moves = {}
class_moves.update(read_tsv_pairs("mojang-class-moves-26.1.tsv"))          # 1.21.x -> 26.1
class_moves.update(read_tsv_pairs("retromod/forge-1.12.2-class-moves.tsv"))  # 1.12.2 MCP -> Mojang

# ---- curated: removed loader/MC classes Retromod bridges or stubs (references LOAD after transform) ----
# Seeded from the shipped 1.12.2 load stubs + the Forge->NeoForge / EventBus bridges (CHANGELOG).
removed_bridged.update({
    "net/minecraftforge/fml/common/IWorldGenerator",                       # #131 stub
    "net/minecraftforge/fml/common/network/IGuiHandler",                   # #132 stub
    "net/minecraftforge/fml/common/network/simpleimpl/IMessage",          # #135 stub
    "net/minecraftforge/fml/common/network/simpleimpl/IMessageHandler",   # #135 stub
    "net/minecraftforge/registries/IForgeRegistryEntry",                   # #134 stub
    "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext",         # #85 bridge
    "net/minecraftforge/fml/common/registry/GameRegistry",                # 1.12.2 lifecycle idiom
})

# ---- curated: removed classes with NO bridge yet (an honest "risky" flag) ----
# Only genuinely-removed CLASSES belong here. A class that still exists but lost a specific
# constructor/method (e.g. CreativeModeTab's old String ctor, #133) must NOT be listed: the probe
# only sees class-level references, so listing a live class would flag every modern mod that uses
# it as RISKY. Constructor/member-level regressions are out of scope for a class-name scan.
removed_no_bridge = {
    "net/minecraft/world/WorldType":                                      # #62 custom world types (removed 1.16)
        "Custom world types were removed in 1.16 (no redirect exists).",
}

# ---- curated: deep-integration / rendering mods that cannot be translated (docs/incompatible-mods.md) ----
# Matched by mod name (case-insensitive substring) OR a referenced package prefix in the jar.
known_incompatible = [
    {"name": "Create", "match": ["create"], "pkg": ["com/simibubi/create"],
     "reason": "Ships Flywheel, a custom GL renderer plus coremods; deep-integration, cannot be translated."},
    {"name": "Flywheel", "match": ["flywheel"], "pkg": ["dev/engine_room/flywheel", "com/jozufozu/flywheel"],
     "reason": "A custom instanced-rendering engine; cannot be translated."},
    {"name": "Veil", "match": ["veil"], "pkg": ["foundry/veil"],
     "reason": "A custom rendering framework (custom pipeline); cannot be translated."},
    {"name": "Sable", "match": ["sable"], "pkg": [],
     "reason": "Built on Veil, so it inherits Veil's custom-pipeline incompatibility."},
    {"name": "OptiFine", "match": ["optifine"], "pkg": ["net/optifine"],
     "reason": "Wraps MC's rendering at a level that does not translate."},
    {"name": "ImmediatelyFast", "match": ["immediatelyfast"], "pkg": [],
     "reason": "A rendering-pipeline replacement; wraps rendering at a level that does not translate."},
    # Deep-integration / coremod-class tech mods (docs/incompatible-mods.md "Specific mods that will never work").
    {"name": "Applied Energistics 2", "match": ["applied energistics", "ae2"], "pkg": ["appeng/"],
     "reason": "Decades of deep MC integration, a custom networking protocol, historically a coremod; the channel/quantum systems are re-architected between MC versions, not renamed."},
    {"name": "Tinkers' Construct", "match": ["tinkers"], "pkg": ["slimeknights/tconstruct"],
     "reason": "Replaces the tool/material system at the JSON-schema and bytecode level; the cross-version schema changes are larger than any redirect table can cover."},
    {"name": "IndustrialCraft / IC2", "match": ["industrialcraft", "ic2"], "pkg": ["ic2/"],
     "reason": "Very deep MC integration with coremod heritage; the energy-network internals are tied to MC's tick scheduler and break on every major update."},
    {"name": "Thaumcraft", "match": ["thaumcraft"], "pkg": ["thaumcraft/"],
     "reason": "Magic systems tied to MC internal data structures that change between versions; redesigned multiple times against specific MC internals."},
    {"name": "Botania (deep mixin variants)", "match": ["botania"], "pkg": ["vazkii/botania"],
     "reason": "Heavily mixin-based with injection points tied to specific bytecode offsets; some features may load but full compatibility does not translate."},
    {"name": "AsyncParticles", "match": ["asyncparticles", "async particles"], "pkg": [],
     "reason": "Wraps the Mixin service itself and @Overwrites particle-engine internals; its own machinery cancels most mixins on a non-matching host and the @Overwrite body cannot be redirected."},
    # Rendering replacement / shader (load at best partially; injections target moving bytecode offsets).
    {"name": "Sodium", "match": ["sodium"], "pkg": ["me/jellysquid/mods/sodium", "net/caffeinemc/mods/sodium"],
     "reason": "Rendering-pipeline mod: mixin injections target specific renderer bytecode offsets that move between versions; partial functionality at best."},
    {"name": "Iris Shaders", "match": ["iris shaders", "irisshaders"], "pkg": ["net/coderbot/iris", "net/irisshaders"],
     "reason": "Shader pipeline: same moving-offset injection problem as Sodium; partial at best."},
    {"name": "Embeddium", "match": ["embeddium"], "pkg": ["org/embeddedt/embeddium"],
     "reason": "A Sodium-family rendering mod: injections target moving renderer offsets; partial at best."},
]

db = {
    "generated": datetime.date.today().isoformat(),
    "retromod_version": retromod_version(),
    # Host MC versions Retromod ships builds for (keep in sync with build-all.sh MC_VERSIONS).
    "hostTargets": ["1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6",
                    "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6",
                    "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11", "26.1", "26.1.1",
                    "26.1.2", "26.2", "26.3"],
    # Oldest source MC a mod can be built for, per loader (Retromod walks it forward from here).
    "minSource": {"forge": "1.12.2", "neoforge": "1.20.1", "fabric": "1.14.4"},
    "renamed": renamed,                       # old internal name -> new (Retromod remaps)
    "classMoves": class_moves,                # MC package reorganizations (Retromod remaps)
    "removedBridged": sorted(removed_bridged),  # removed but stubbed/bridged (loads after transform)
    "removedNoBridge": removed_no_bridge,     # removed, no redirect yet (risky) -> reason
    "knownIncompatible": known_incompatible,
}

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as f:
    json.dump(db, f, separators=(",", ":"), ensure_ascii=False)

size = os.path.getsize(OUT)
print(f"wrote {OUT}")
print(f"  retromod {db['retromod_version']}, generated {db['generated']}")
print(f"  renamed={len(renamed)}  classMoves={len(class_moves)}  "
      f"removedBridged={len(removed_bridged)}  removedNoBridge={len(removed_no_bridge)}  "
      f"knownIncompatible={len(known_incompatible)}")
print(f"  size={size/1024:.1f} KB")
