# API Compatibility

Retromod repairs specific API changes that appear in real mods. Support is usually narrower than an entire library version, so a listed API should be read as “Retromod knows about parts of this API,” not “every release and feature works.”

## How API Repairs Work

Depending on the change, Retromod may:

- rename or move a referenced class, method, or field
- adapt a changed method descriptor
- replace a deleted call with a small bridge
- embed a missing compatibility class into the transformed mod
- relax loader metadata so the mod can be tested

Bridges are selected from the references a mod actually uses. Retromod does not download API code at runtime.

## API Families With Active Support

| Area | Examples |
|---|---|
| Fabric | Fabric API events, networking, rendering hooks, tags, item groups, Mod Menu, Cloth Config, REI, EMI, Trinkets, Cardinal Components, LibGui |
| Forge and NeoForge | registries, event buses, capabilities, configuration, JEI, Curios, Mekanism |
| Cross-loader | Architectury, GeckoLib, YACL, MixinExtras, selected AE2 and Botania API moves |
| Legacy | Baubles to Curios, NEI to JEI, RF to Forge Energy, WAILA to Jade, old Forge lifecycle and registration types |

Coverage varies within every row. The [compatibility database](https://bownlux.github.io/Retromod/compatdb/) is the best place to check a real mod and version.

## What a Bridge Cannot Do

A bridge can preserve a small, well-understood contract. It cannot recreate a renderer, networking protocol, registry model, or gameplay system that was redesigned from scratch.

Mixin handlers are especially sensitive. Retromod can repair known parameter changes and renamed targets, but it cannot safely guess a new method body or local-variable layout.

Some repairs intentionally leave one feature inactive so the rest of a mod can load. Logs and compatibility reports should say when that happens.

## Requesting Support

Open a [GitHub issue](https://github.com/Bownlux/Retromod/issues) with:

- the API and versions involved
- an example mod that uses the missing path
- the source and host Minecraft versions
- the loader and Retromod version
- `logs/latest.log` from the failed launch

A concrete failing mod is much more useful than a request for blanket support.
