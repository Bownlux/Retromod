---
title: FAQ
nav_order: 10
---

# FAQ

## Is Retromod safe?

Retromod is open source, runs inside Minecraft, and is offline by default. Runtime transforms stay inside the selected game directory, and original mod jars are kept in `retromod-input/processed/`, `mods/retromod-backups/`, or the game-directory `retromod-backups/` health-recovery folder. The standalone CLI can also read and write paths you explicitly give it.

It does rewrite third-party bytecode. Use mods from sources you trust, back up important worlds yourself, and test save-affecting mods in a copied instance first. Retromod's jar backup is not a world backup and it does not sandbox a mod after launch.

## Does it need internet access?

No. Normal transformation, caching, and verification are local. The native-version lookup is off by default and only queries Modrinth after `check_for_native_versions` or `-Dretromod.checkForNativeVersions=true` opts in. CLI `archive download` and `archive preload` use loader Maven repositories only after a prompt, unless you explicitly pass `--yes`.

## Which versions work?

Retromod runs on Minecraft 1.20 through 26.2. Source support depends on the loader:

- Fabric: 1.14.4+
- NeoForge: 1.20.1+
- Forge: 1.12.2+

Coverage before 1.16.5 is experimental.

These are source-version floors, not host artifacts. Published host jars cover Fabric and Forge 1.20 through 26.2, plus NeoForge 1.20.1 through 26.2.

Pre-26.1 Fabric hosts use targeted intermediary bridges instead of the full intermediary-to-Mojang remap. Common old text, entity, material, identifier, model, and entity-type migrations are covered, but redesigned APIs can still require a manual port.

## Does it work on servers?

Yes. Install Retromod on every side that loads transformed code. Clients do not need it for a genuinely server-only mod.

Paper, Spigot, Bukkit, and Purpur plugins are not supported. Retromod targets Fabric, NeoForge, and Forge mods.

## Does it work with modpacks?

Yes. Keep old Fabric mods in `retromod-input/` so Retromod can process them before Fabric's version check. For repeatable distribution, prepare the pack with the CLI.

## What happens to dependencies?

Retromod relaxes stale version ranges when it knows the dependency can be bridged. It does not invent a missing library. Install required dependencies unless Retromod provides a documented polyfill.

## Can I run an old Forge mod on NeoForge?

Sometimes. Retromod bridges selected common 1.20.1 Forge metadata, registration, event-bus, networking, and removed-class paths. It is not a complete Forge runtime. Mods with deep registry lifecycle, packet delivery, data generation, rendering, or other Forge internals are still safer on a matching Forge host.

## Can Retromod repair Mixins automatically?

For a narrow, proven subset. At game launch Retromod indexes the installed Minecraft classes. The CLI gets the same target facts from `--mc-jar <target.jar>`. It can repair unique parameter-addition changes and selected zero-capture or exact-prefix handlers. It refuses ambiguous overloads, constructors, reordered or removed parameters, return-type changes, semantic local captures, unsafe annotations, and `remap = false` scopes.

Curated adapters cover a few semantic migrations. Other save, worldgen, renderer, or networking changes still need a real bridge and feature testing.

## Should Minecraft 26.2 use OpenGL or Vulkan?

Use OpenGL for translated mods. Old rendering code often assumes OpenGL, while Vulkan is the new default. Retromod selects OpenGL automatically unless you already chose a backend.

## Does it work with OptiFine, Sodium, Iris, or Create?

Simple integrations may work, but renderer replacements and deep coremods are the hardest category and often cannot be translated. Check [Mods That Can't Be Translated]({{ '/incompatible-mods' | relative_url }}) and the [compatibility database]({{ '/compatdb' | relative_url }}).

## What does a modified-build warning mean?

The running classes do not match the embedded release hash. That can be a legitimate local build or fork. Compare the jar's SHA-256 with the value on the official download page if the change was unexpected. See [Authenticity]({{ '/authenticity' | relative_url }}).

## Where are my files written?

Runtime inputs and originals use `retromod-input/`, `retromod-input/processed/`, `mods/retromod-backups/`, and the game-directory `retromod-backups/` health-recovery folder. Generated reports use `config/retromod/verify-reports/`. The normal AOT cache is `config/retromod/aot-cache/`; full AOT state is `retromod-cache/full-aot/`. API archives use `config/retromod/api-archive/`. CLI `transform` output defaults to a sibling jar, `batch` uses an input-folder `retromod-output/`, and `aot` uses the normal AOT cache unless you choose `--output`. The CLI initializes the standard input and backup folders in its current working directory.

## Can I use Retromod commercially?

Yes. Retromod uses the MIT license. You still need to follow the licenses of Minecraft, the loader, and every mod in your pack.

## Can I contribute?

Yes. Mapping fixes and focused redirects are good first changes. See [Contributing]({{ '/contributing' | relative_url }}).
