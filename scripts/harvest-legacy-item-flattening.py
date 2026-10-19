#!/usr/bin/env python3
"""Derive the 1.12 item and entity id table for mod data from vanilla client jars.

Usage:
    python3 -I scripts/harvest-legacy-item-flattening.py OLD.jar BOUNDARY.jar CURRENT.jar \
        [VERSION=RELEASE.jar ...] > src/main/resources/retromod/legacy-item-flattening.tsv

OLD is the last pre-Flattening client (1.12.2), BOUNDARY the first post-Flattening one (1.13),
and CURRENT the newest client Retromod builds for. Each VERSION=RELEASE.jar is a release in
between, used only to check when a later rename happened.

The 1.13 rows are read from the BOUNDARY jar's own data fixers, so nothing is typed by hand:

  meta      the item flattening map, "minecraft:wool.14" to "minecraft:red_wool"
  renames   the whole-id renames 1.13 applied to items and entities, such as mob_spawner to
            spawner and villager_golem to iron_golem, kept only when the old id was a 1.12
            item (it has an item model in OLD) or the new id is a 1.13 entity

A flattened id that 1.13 renamed again inside the same release (fish.2 became clownfish, then
tropical_fish) is chained to the name 1.13 shipped.

The data fixers convert items in saved worlds, which is not always what a recipe meant. Every
1.12 recipe in OLD is translated through the table and compared with BOUNDARY's recipe for the
same result: each ingredient must still accept at least one item the 1.13 recipe accepts there.
A row that fails this is overridden in DATA_MEANINGS, with the 1.13 recipes as the reason.

Later renames of ids this table produces are listed in LATER below, each with the release that
made it. The script checks every one against the release jars on both sides and fails when an id
the table produces disappears from a release without a LATER row, so a missing rename cannot
pass silently. Wildcard family tags in FAMILIES are checked the same way: the tag must exist in
every release given, and in 1.13 it must hold every member of the family.

Reads jars as zip archives only. Nothing from them is executed.
"""
import json
import re
import struct
import sys
import zipfile

# Renames after 1.13 of ids the 1.13 rows produce: (kind, old, new, release, source).
# The release is the first one that reads the new id; the check below confirms it lies between
# the newest given jar that still has the old id and the oldest that has the new one.
LATER = [
    ("item", "minecraft:stone_slab", "minecraft:smooth_stone_slab", "1.14",
     "1.14 block and item rename; stone_slab became a new plain stone slab"),
    ("item", "minecraft:sign", "minecraft:oak_sign", "1.14", "1.14 block and item rename"),
    ("item", "minecraft:rose_red", "minecraft:red_dye", "1.14", "DyeItemRenameFix"),
    ("item", "minecraft:cactus_green", "minecraft:green_dye", "1.14", "DyeItemRenameFix"),
    ("item", "minecraft:dandelion_yellow", "minecraft:yellow_dye", "1.14", "DyeItemRenameFix"),
    ("entity", "minecraft:zombie_pigman", "minecraft:zombified_piglin", "1.16",
     "EntityZombifiedPiglinRenameFix"),
    ("item", "minecraft:grass_path", "minecraft:dirt_path", "1.17", "1.17 block and item rename"),
    ("item", "minecraft:grass", "minecraft:short_grass", "1.20.3", "1.20.3 block and item rename"),
]

# Ids that keep their name in a later release but change meaning, so their disappearance check
# does not apply: the old meaning moved to the LATER row's new id.
REPURPOSED = {"minecraft:stone_slab"}

# Fixer rows whose meaning in data differs from the world conversion: (old, meta) to the item the
# fixer names, which must still match, the item a made or dropped reference becomes, and the other
# items an accepting reference also takes.
# 1.13's fixer turns pumpkin.0 into carved_pumpkin because the 1.12 block had a face, but 1.13
# reused minecraft:pumpkin for the uncarved block, and its own recipes take that one for
# pumpkin_pie and pumpkin_seeds and carved_pumpkin for jack_o_lantern. A 1.12 recipe could not
# ask for either, so it accepts both, and a pumpkin it makes or drops is the uncarved one.
DATA_MEANINGS = {
    ("minecraft:pumpkin", "0"): ("minecraft:carved_pumpkin", "minecraft:pumpkin",
                                 ["minecraft:carved_pumpkin"]),
}

# 1.12 ids whose wildcard (data 32767, or no data in a predicate) a vanilla tag covers.
FAMILIES = {
    "minecraft:wool": "minecraft:wool",
    "minecraft:planks": "minecraft:planks",
    "minecraft:log": "minecraft:logs",
    "minecraft:log2": "minecraft:logs",
    "minecraft:leaves": "minecraft:leaves",
    "minecraft:leaves2": "minecraft:leaves",
    "minecraft:sapling": "minecraft:saplings",
    "minecraft:wooden_slab": "minecraft:wooden_slabs",
    "minecraft:banner": "minecraft:banners",
}


# ---------------------------------------------------------------------------------------------
# Class files: the string constants each method loads, in bytecode order

def _opcode_lengths():
    lengths = [1] * 256
    for op in (0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc):
        lengths[op] = 2
    for op in (0x11, 0x13, 0x14, 0x84, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xbb, 0xbd,
               0xc0, 0xc1, 0xc6, 0xc7, *range(0x99, 0xa9)):
        lengths[op] = 3
    lengths[0xc5] = 4
    for op in (0xb9, 0xba, 0xc8, 0xc9):
        lengths[op] = 5
    return lengths


OPCODE_LENGTHS = _opcode_lengths()


def instruction_length(code, at):
    op = code[at]
    if op == 0xaa:  # tableswitch
        base = (at + 4) & ~3
        low, high = struct.unpack(">ii", code[base + 4:base + 12])
        return base - at + 12 + 4 * (high - low + 1)
    if op == 0xab:  # lookupswitch
        base = (at + 4) & ~3
        pairs = struct.unpack(">i", code[base + 4:base + 8])[0]
        return base - at + 8 + 8 * pairs
    if op == 0xc4:  # wide
        return 6 if code[at + 1] == 0x84 else 4
    return OPCODE_LENGTHS[op]


def method_strings(data):
    """[(name + descriptor, [string constants loaded by ldc, in order])] for one class file."""
    count = struct.unpack(">H", data[8:10])[0]
    pool = [None] * count
    at, index = 10, 1
    while index < count:
        tag = data[at]
        at += 1
        if tag == 1:
            length = struct.unpack(">H", data[at:at + 2])[0]
            pool[index] = ("utf8", data[at + 2:at + 2 + length].decode("utf-8", "replace"))
            at += 2 + length
        elif tag in (3, 4):
            at += 4
        elif tag in (5, 6):
            at += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = (tag, struct.unpack(">H", data[at:at + 2])[0])
            at += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            at += 4
        elif tag == 15:
            at += 3
        else:
            raise ValueError("unknown constant pool tag %d" % tag)
        index += 1

    def utf8(i):
        return pool[i][1]

    def string_at(i):
        entry = pool[i]
        return utf8(entry[1]) if entry and entry[0] == 8 else None

    at += 6
    interfaces = struct.unpack(">H", data[at:at + 2])[0]
    at += 2 + 2 * interfaces
    out = []
    for is_method in (False, True):
        members = struct.unpack(">H", data[at:at + 2])[0]
        at += 2
        for _ in range(members):
            _, name, descriptor, attributes = struct.unpack(">HHHH", data[at:at + 8])
            at += 8
            for _ in range(attributes):
                attribute, length = struct.unpack(">HI", data[at:at + 6])
                at += 6
                if is_method and utf8(attribute) == "Code":
                    code_length = struct.unpack(">I", data[at + 4:at + 8])[0]
                    code = data[at + 8:at + 8 + code_length]
                    strings, pc = [], 0
                    while pc < len(code):
                        op = code[pc]
                        if op == 0x12:
                            value = string_at(code[pc + 1])
                        elif op == 0x13:
                            value = string_at(struct.unpack(">H", code[pc + 1:pc + 3])[0])
                        else:
                            value = None
                        if value is not None:
                            strings.append(value)
                        pc += instruction_length(code, pc)
                    out.append((utf8(name) + utf8(descriptor), strings))
                at += length
    return out


def find_class(jar, marker):
    """The method strings of the first class whose constants include the marker string."""
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if not name.endswith(".class"):
                continue
            data = archive.read(name)
            if marker.encode() not in data:
                continue
            methods = method_strings(data)
            if any(marker in strings for _, strings in methods):
                return methods
    raise SystemExit("no class in %s names %r" % (jar, marker))


def pairs(strings):
    ids = [s for s in strings if s.startswith("minecraft:")]
    if len(ids) % 2:
        raise SystemExit("odd number of ids in a rename map: %r" % ids)
    return list(zip(ids[0::2], ids[1::2]))


# ---------------------------------------------------------------------------------------------
# Registries, as far as a client jar shows them

def item_ids(jar):
    """Ids with an item model (or, from 1.21.4, an item definition): every item vanilla shows."""
    found = set()
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            for prefix in ("assets/minecraft/models/item/", "assets/minecraft/items/"):
                if name.startswith(prefix) and name.endswith(".json") and "/" not in name[len(prefix):]:
                    found.add("minecraft:" + name[len(prefix):-5])
    return found


def lang_ids(jar, *kinds):
    """Ids the jar's English language file names as one of kinds, such as item or block."""
    with zipfile.ZipFile(jar) as archive:
        lang = json.loads(archive.read("assets/minecraft/lang/en_us.json"))
    return {"minecraft:" + key.split(".")[2] for key in lang
            if key.split(".")[0] in kinds and key.count(".") == 2
            and key.split(".")[1] == "minecraft"}


def entity_ids(jar):
    return lang_ids(jar, "entity")


def legacy_entity_ids(jar):
    """The entity names a pre-1.13 jar registers, from the method that registers villager_golem."""
    for _, strings in find_class(jar, "villager_golem"):
        if "villager_golem" in strings and "xp_orb" in strings:
            return {"minecraft:" + s for s in strings if re.fullmatch(r"[a-z0-9_]+", s)}
    raise SystemExit("no entity registration in %s" % jar)


def item_tags(jar):
    tags = {}
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            parts = name.split("/")
            if (len(parts) >= 5 and parts[:3] == ["data", "minecraft", "tags"]
                    and parts[3] in ("item", "items") and name.endswith(".json")):
                tags["minecraft:" + "/".join(parts[4:])[:-5]] = json.loads(archive.read(name))["values"]
    return tags


def version_key(version):
    return tuple(int(x) for x in re.findall(r"\d+", version))


# ---------------------------------------------------------------------------------------------
# Vanilla recipe pairs: what the table makes of 1.12's own recipes, against 1.13's

def crafting_recipes(jar, folder):
    """{file name: recipe} for the shaped and shapeless recipes in one recipe folder."""
    recipes = {}
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if name.startswith(folder) and name.endswith(".json"):
                recipe = json.loads(archive.read(name))
                if recipe.get("type") in ("crafting_shaped", "crafting_shapeless",
                                          "minecraft:crafting_shaped", "minecraft:crafting_shapeless"):
                    recipes[name[len(folder):]] = recipe
    return recipes


def slots(recipe, accepted):
    """The item set each slot of a crafting recipe accepts, read through accepted(ingredient)."""
    if recipe["type"].endswith("crafting_shaped"):
        return [accepted(recipe["key"][symbol]) for row in recipe["pattern"] for symbol in row
                if symbol != " "]
    return [accepted(ingredient) for ingredient in recipe["ingredients"]]


def each_slot_overlaps(mine, theirs):
    """Whether the slots pair up one to one so that every pair shares an item."""
    if len(mine) != len(theirs):
        return False
    if not mine:
        return True
    first, rest = mine[0], mine[1:]
    return any(first & slot and each_slot_overlaps(rest, theirs[:i] + theirs[i + 1:])
               for i, slot in enumerate(theirs))


def recipe_pair_problems(old_jar, boundary_jar, meta_rows, whole_rows, also_rows):
    """1.12 recipes whose translation shares no item with 1.13's recipe for the same result."""
    flattened, renamed, also = {}, dict(whole_rows), {}
    for old, meta, new in meta_rows:
        flattened.setdefault(old, {})[int(meta)] = new
    for old, meta, new in also_rows:
        also.setdefault((old, int(meta)), set()).add(new)
    tags = item_tags(boundary_jar)

    def tag_members(tag):
        members = set()
        for value in tags.get(tag, []):
            members |= tag_members(value[1:]) if value.startswith("#") else {value}
        return members

    def namespaced(item):
        return item if ":" in item else "minecraft:" + item

    def accepted_12(ingredient):
        if isinstance(ingredient, list):
            return set().union(*(accepted_12(alternative) for alternative in ingredient))
        item, data = namespaced(ingredient["item"]), ingredient.get("data", 0)
        values = flattened.get(item)
        if values is None:
            return {renamed.get(item, item)} | also.get((item, 0), set())
        metas = list(values) if data == 32767 else [data]
        items = set()
        for meta in metas:
            items |= {values.get(meta, "unknown %s data %s" % (item, meta))} | also.get((item, meta), set())
        return items

    def accepted_13(ingredient):
        if isinstance(ingredient, list):
            return set().union(*(accepted_13(alternative) for alternative in ingredient))
        return tag_members(ingredient["tag"]) if "tag" in ingredient else {namespaced(ingredient["item"])}

    def made_12(result):
        item, data = namespaced(result["item"]), result.get("data", 0)
        values = flattened.get(item)
        return renamed.get(item, item) if values is None else values.get(data)

    by_result = {}
    for recipe in crafting_recipes(boundary_jar, "data/minecraft/recipes/").values():
        by_result.setdefault(namespaced(recipe["result"]["item"]), []).append(slots(recipe, accepted_13))
    problems = []
    for name, recipe in sorted(crafting_recipes(old_jar, "assets/minecraft/recipes/").items()):
        made = made_12(recipe["result"])
        mine = slots(recipe, accepted_12)
        if not any(each_slot_overlaps(mine, theirs) for theirs in by_result.get(made, [])):
            problems.append("1.12 recipe %s, translated to make %s from %s, shares no item with any "
                            "1.13 recipe for it" % (name, made, [sorted(slot) for slot in mine]))
    return problems


# ---------------------------------------------------------------------------------------------

def main():
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    old_jar, boundary_jar, current_jar = sys.argv[1:4]
    releases = [("1.13", boundary_jar)]
    for argument in sys.argv[4:]:
        version, jar = argument.split("=", 1)
        releases.append((version, jar))
    releases.append(("current", current_jar))
    releases.sort(key=lambda r: version_key(r[0]) if r[0] != "current" else (999,))

    old_items = item_ids(old_jar)
    items_13 = item_ids(boundary_jar)
    entities_13 = entity_ids(boundary_jar)

    # The 1.13 whole-id renames: the entity, block and item rename map, plus the single-purpose
    # renamers registered with the data fixers (melon, shulker box, and snapshot-only names).
    renames = dict(pairs(next(s for n, s in find_class(boundary_jar, "EntityTheRenameningBlock")
                             if n.startswith("<clinit>"))))
    for name, strings in find_class(boundary_jar, "Unable to build datafixers"):
        if name.endswith("(Ljava/lang/String;)Ljava/lang/String;"):
            for old, new in pairs(strings):
                renames.setdefault(old, new)

    def rename_13(item):
        new = renames.get(item, item)
        return new if new in items_13 else item

    flattening = find_class(boundary_jar, "ItemInstanceTheFlatteningFix")
    meta_rows = []
    for name, strings in flattening:
        if name.endswith("(Ljava/util/HashMap;)V"):
            for key, new in zip(strings[0::2], strings[1::2]):
                old, meta = key.rsplit(".", 1)
                meta_rows.append((old, meta, rename_13(new)))

    whole_rows = sorted((old, new) for old, new in renames.items()
                        if old in old_items and old not in {r[0] for r in meta_rows}
                        and new in items_13 and old != new)
    old_entities = legacy_entity_ids(old_jar)
    entity_rows = sorted((old, new) for old, new in renames.items()
                         if old in old_entities and new in entities_13 and old not in entities_13)

    problems = []
    also_rows = []
    for index, (old, meta, new) in enumerate(meta_rows):
        meaning = DATA_MEANINGS.get((old, meta))
        if meaning is None:
            continue
        fixer, made, accepted = meaning
        if new != fixer:
            problems.append("%s.%s: the 1.13 fixer now names %s, not %s; review DATA_MEANINGS"
                            % (old, meta, new, fixer))
        meta_rows[index] = (old, meta, made)
        also_rows.extend((old, meta, item) for item in accepted)
    for key in DATA_MEANINGS.keys() - {(old, meta) for old, meta, _ in meta_rows}:
        problems.append("%s.%s in DATA_MEANINGS is not a 1.13 fixer row" % key)
    for old, meta, new in meta_rows + also_rows:
        if new not in items_13:
            problems.append("1.13 has no item %s for %s.%s" % (new, old, meta))

    # Everything a 1.12 reference can turn into on 1.13: flattened and renamed ids, and every
    # 1.12 item 1.13 kept under the same name. An item model alone is not proof of an item
    # (broken_elytra is a model state), so unchanged ids must also be named in 1.13's language.
    named_13 = lang_ids(boundary_jar, "item", "block")
    produced = {new for _, _, new in meta_rows + also_rows} | {new for _, new in whole_rows}
    produced |= {item for item in old_items if item in items_13 and item in named_13}

    later_by_old = {old: (kind, new, since) for kind, old, new, since, _ in LATER}
    jars = {version: (item_ids(jar), entity_ids(jar)) for version, jar in releases}

    for kind, old, new, since, _ in LATER:
        index = 0 if kind == "item" else 1
        before = [v for v, _ in releases if v != "current" and version_key(v) < version_key(since)]
        after = [v for v, _ in releases if v == "current" or version_key(v) >= version_key(since)]
        if not before or not after:
            problems.append("%s -> %s: no release jar on both sides of %s" % (old, new, since))
            continue
        if old not in jars[before[-1]][index] or new in jars[before[-1]][index]:
            problems.append("%s -> %s: %s does not show the old id only" % (old, new, before[-1]))
        if new not in jars[after[0]][index]:
            problems.append("%s -> %s: %s lacks the new id" % (old, new, after[0]))
        if old not in REPURPOSED and old in jars[after[0]][index]:
            problems.append("%s -> %s: %s still has the old id" % (old, new, after[0]))

    for version, _ in releases:
        present = jars[version][0]
        for item in sorted(produced):
            current, chained = item, True
            while current in later_by_old:
                _, successor, since = later_by_old[current]
                if version != "current" and version_key(version) < version_key(since):
                    break
                current = successor
            if current not in present:
                problems.append("%s: %s (from a 1.12 reference) is missing and no later rename "
                                "covers it" % (version, current))

    tags_by_release = {version: item_tags(jar) for version, jar in releases}
    for old, tag in sorted(FAMILIES.items()):
        members = {new for o, _, new in meta_rows if o == old}
        for version, tags in tags_by_release.items():
            if tag not in tags:
                problems.append("%s: tag %s for %s is missing" % (version, tag, old))
        values = set(tags_by_release["1.13"].get(tag, []))
        expanded = set()
        for value in values:
            expanded |= set(tags_by_release["1.13"].get(value[1:], [])) if value.startswith("#") else {value}
        if not members <= expanded:
            problems.append("1.13 tag %s lacks %s" % (tag, sorted(members - expanded)))

    problems += recipe_pair_problems(old_jar, boundary_jar, meta_rows, whole_rows, also_rows)

    if problems:
        sys.stderr.write("\n".join(problems) + "\n")
        raise SystemExit(1)

    print("# 1.12 item and entity ids in mod data, and what each one became.")
    print("#")
    print("# Format: KIND<TAB>OLD<TAB>META<TAB>NEW<TAB>SINCE")
    print("#   item    OLD with META (a number, or - for every value) became NEW in SINCE")
    print("#   entity  OLD became NEW in SINCE")
    print("#   family  every META value of OLD is covered by the vanilla tag NEW (META is *)")
    print("#   also    an ingredient or predicate naming OLD with META accepts NEW as well")
    print("# Rows with SINCE 1.13 read 1.12 ids. Later rows read the id an earlier row produced,")
    print("# so a reference is carried to the name its target version reads, one release at a time.")
    print("#")
    print("# Derived by scripts/harvest-legacy-item-flattening.py from the 1.12.2 and 1.13 client jars:")
    print("# the 1.13 rows are the 1.13 item flattening map and the 1.13 rename maps, read from the")
    print("# 1.13 jar's data fixers. Later rows and families were checked against the client jars of")
    print("# " + ", ".join(v for v, _ in releases if v != "current") + " and the current release: the old")
    print("# id is present before SINCE, the new id from SINCE on, and nothing the 1.13 rows produce")
    print("# disappears from a release without a later row. Regenerate after a vanilla rename.")
    print("# Every 1.12 vanilla recipe, translated through these rows, shares an item in each ingredient")
    print("# with 1.13's recipe for the same result. Where the fixer disagreed, the rows follow those")
    print("# recipes instead: " + ", ".join("%s %s" % key for key in sorted(DATA_MEANINGS)) + ".")
    for old, meta, new in meta_rows:
        print("item\t%s\t%s\t%s\t1.13" % (old, meta, new))
    for old, meta, new in also_rows:
        print("also\t%s\t%s\t%s\t1.13" % (old, meta, new))
    for old, new in whole_rows:
        print("item\t%s\t-\t%s\t1.13" % (old, new))
    for old, new in entity_rows:
        print("entity\t%s\t-\t%s\t1.13" % (old, new))
    for kind, old, new, since, _ in LATER:
        print("%s\t%s\t-\t%s\t%s" % (kind, old, new, since))
    for old, tag in sorted(FAMILIES.items()):
        print("family\t%s\t*\t#%s\t1.13" % (old, tag))


if __name__ == "__main__":
    main()
