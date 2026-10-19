#!/usr/bin/env python3
"""Derive src/main/resources/retromod/vanilla-id-renames.tsv from vanilla client jars.

Usage:
  python3 -I scripts/harvest-vanilla-id-renames.py <version>=<client.jar> ... > vanilla-id-renames.tsv

Give the jars of every release you have from 1.13 on, oldest first. The script only reads the
jars as zip files.

For each pair of adjacent versions it lists the block, item, entity and enchantment ids that
disappear. An id's registry membership is read from the client's own files: a blockstate file
for a block, a language key plus an item model or item definition for an item (the language file
keeps some keys after their item is gone), a language key that the class registering entity
types also names for an entity (1.21.2 kept the boat and chest_boat keys after removing those
types), and the enchantment file, or before 1.21 the language key less one the same release
renamed, for an enchantment.

A disappearing id is a rename when the newer client's own world upgrader pairs it with exactly
one new id of the same registry: the two names sit next to each other in one class's constant
pool, the way a rename table lists them. Without that pairing, a rename needs one new id whose
definition file matches once the old name is swapped for the new one (the blockstate for a block,
the item model or item definition for an item, the loot table for an entity, the language string
for an enchantment). When several match, as the copper chains do next to the iron one, the one
whose textures are byte-identical to the old id's wins. A successor claimed by two old ids is no
rename. Every other disappearing id is written as removed, which also covers splits.

A row is dropped when its old id comes back in any later version, because a returning id means
something else from then on and no rewrite keyed on the string alone can tell the two apart.
"""
import json
import re
import sys
import zipfile

ID = r"[a-z0-9_]+"


def version_key(version):
    parts = [int(p) for p in re.findall(r"\d+", version)[:3]]
    while len(parts) < 3:
        parts.append(0)
    return tuple(parts)


class Release:
    def __init__(self, version, path):
        self.version = version
        self.zip = zipfile.ZipFile(path)
        self.names = set(self.zip.namelist())
        lang = json.loads(self.zip.read("assets/minecraft/lang/en_us.json"))
        self.lang = lang
        self.ids = {
            "block": {n[len("assets/minecraft/blockstates/"):-5] for n in self.names
                      if re.fullmatch(r"assets/minecraft/blockstates/" + ID + r"\.json", n)},
            "item": self._lang_ids(lang, ("item", "block")) & self._item_files(),
            "entity": self._entity_ids(lang),
            "enchantment": self._enchantment_ids(lang),
        }

    def _entity_ids(self, lang):
        """The language file keeps an entity's key after its type is gone, so an entity counts only
        when the class that registers entity types names it too. That class is the one whose
        constant pool holds the most of the language file's entity names; the names it lacks are
        removed types and labels such as killer_bunny."""
        ids = self._lang_ids(lang, ("entity",))
        registered = set()
        for pool in self.class_pools():
            named = ids & set(pool)
            if len(named) > len(registered):
                registered = named
        return registered

    def class_pools(self):
        """Each class file's CONSTANT_Utf8 strings, in pool order, read one class at a time."""
        for name in self.names:
            if name.endswith(".class"):
                yield utf8_constants(self.zip.read(name))

    def _enchantment_ids(self, lang):
        """Enchantments are files from 1.21. Before that only the language file names them, and it
        keeps a renamed enchantment's old key beside the new one with the same text, so the old key
        is dropped when this release's world upgrader also pairs the two names."""
        files = {n[len("data/minecraft/enchantment/"):-5] for n in self.names
                 if re.fullmatch(r"data/minecraft/enchantment/" + ID + r"\.json", n)}
        if files:
            return files
        ids = self._lang_ids(lang, ("enchantment",))
        text = {i: lang["enchantment.minecraft." + i] for i in ids}
        stale = {old for old in ids for new in ids
                 if old != new and text[old] == text[new]
                 and ("minecraft:" + old, "minecraft:" + new) in fixer_pairs(self)}
        return ids - stale

    @staticmethod
    def _lang_ids(lang, prefixes):
        out = set()
        for key in lang:
            for prefix in prefixes:
                m = re.fullmatch(prefix + r"\.minecraft\.(" + ID + ")", key)
                if m:
                    out.add(m.group(1))
        return out

    def _item_files(self):
        out = set()
        for n in self.names:
            m = re.fullmatch(r"assets/minecraft/(?:items|models/item)/(" + ID + r")\.json", n)
            if m:
                out.add(m.group(1))
        return out

    def read(self, name):
        return self.zip.read(name).decode("utf-8") if name in self.names else None

    def definition(self, registry, id_):
        if registry == "block":
            return self.read(f"assets/minecraft/blockstates/{id_}.json")
        if registry == "item":
            return (self.read(f"assets/minecraft/items/{id_}.json")
                    or self.read(f"assets/minecraft/models/item/{id_}.json"))
        if registry == "entity":
            for folder in ("loot_table", "loot_tables"):
                text = self.read(f"data/minecraft/{folder}/entities/{id_}.json")
                if text is not None:
                    return text
            return None
        if registry == "enchantment":
            return self.lang.get(f"enchantment.minecraft.{id_}")
        return None


def substitute(text, old, new):
    return re.sub(r"(?<![a-z0-9_])" + re.escape(old) + r"(?![a-z0-9_])", new, text)


def normalized(text):
    try:
        return json.dumps(json.loads(text), sort_keys=True)
    except (ValueError, TypeError):
        return text


def same_definition(a, b, registry, old, new):
    before, after = a.definition(registry, old), b.definition(registry, new)
    if before is None or after is None:
        return False
    if registry == "enchantment":
        return before == after
    return normalized(substitute(before, old, new)) == normalized(after)


def texture_evidence(a, b, old, new):
    """How many of the old id's textures exist byte-identical under the new name."""
    count = 0
    for name in a.names:
        if not name.startswith("assets/minecraft/textures/") or not name.endswith(".png"):
            continue
        base = name.rsplit("/", 1)[1][:-4]
        if not re.search(r"(?<![a-z0-9])" + re.escape(old) + r"(?![a-z0-9])", base):
            continue
        swapped = name[: name.rfind("/") + 1] + substitute(base, old, new) + ".png"
        if swapped in b.names and b.zip.read(swapped) == a.zip.read(name):
            count += 1
    return count


def utf8_constants(data):
    """The CONSTANT_Utf8 strings of one class file, in pool order."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return []
    count = int.from_bytes(data[8:10], "big")
    i, index, out = 10, 1, []
    sizes = {7: 2, 8: 2, 16: 2, 19: 2, 20: 2, 3: 4, 4: 4, 9: 4, 10: 4, 11: 4, 12: 4, 17: 4, 18: 4, 15: 3}
    while index < count:
        tag = data[i]
        i += 1
        if tag == 1:
            length = int.from_bytes(data[i:i + 2], "big")
            out.append(data[i + 2:i + 2 + length].decode("utf-8", "replace"))
            i += 2 + length
        elif tag in (5, 6):
            i += 8
            index += 1
        elif tag in sizes:
            i += sizes[tag]
        else:
            return out
        index += 1
    return out


def fixer_pairs(release):
    """Pairs of minecraft ids that follow each other in a class's constant pool."""
    if not hasattr(release, "_pairs"):
        pairs = set()
        for pool in release.class_pools():
            ids = [c for c in pool if c.startswith("minecraft:")]
            pairs.update(zip(ids, ids[1:]))
        release._pairs = pairs
    return release._pairs


def pick(a, b, old, new, found):
    paired = [n for n in new if ("minecraft:" + old, "minecraft:" + n) in fixer_pairs(b)]
    if len(paired) == 1:
        return paired[0]
    if len(found) == 1:
        return found[0]
    if not found:
        return None
    scores = {n: texture_evidence(a, b, old, n) for n in found}
    best = max(scores.values())
    winners = [n for n, s in scores.items() if s == best]
    return winners[0] if best > 0 and len(winners) == 1 else None


def main():
    releases = []
    for arg in sys.argv[1:]:
        version, path = arg.split("=", 1)
        releases.append(Release(version, path))
    releases.sort(key=lambda r: version_key(r.version))

    rows = []
    for i in range(1, len(releases)):
        a, b = releases[i - 1], releases[i]
        for registry in ("block", "item", "entity", "enchantment"):
            gone = sorted(a.ids[registry] - b.ids[registry])
            new = sorted(b.ids[registry] - a.ids[registry])
            chosen = {}
            for old in gone:
                found = [n for n in new if same_definition(a, b, registry, old, n)]
                chosen[old] = pick(a, b, old, new, found)
            claims = {}
            for old, n in chosen.items():
                if n:
                    claims.setdefault(n, []).append(old)
            for old in gone:
                if any(old in r.ids[registry] for r in releases[i:]):
                    continue
                successor = chosen[old] if chosen[old] and len(claims[chosen[old]]) == 1 else None
                rows.append((registry, "minecraft:" + old,
                             "minecraft:" + successor if successor else "-", b.version, a.version))

    print("# registry\told_id\tnew_id\trenamed_in")
    print("#")
    print("# Vanilla ids that left the game after the Flattening, generated by")
    print("# scripts/harvest-vanilla-id-renames.py from the client jars of "
          + ", ".join(r.version for r in releases) + ".")
    print("# new_id is the one successor whose definition matches once the name is swapped, or - when")
    print("# the id was removed or split with no single successor. renamed_in is the first release in")
    print("# that list without the old id; the release before it in the list still has it.")
    for registry, old, new, version, before in rows:
        print(f"{registry}\t{old}\t{new}\t{version}")


if __name__ == "__main__":
    main()
