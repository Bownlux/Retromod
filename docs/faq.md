---
title: FAQ
nav_order: 10
---

# FAQ

## Is Retromod safe?

Retromod is open source, runs inside Minecraft, and is offline by default. It only works inside the game directory and keeps original mod jars as backups.

It does rewrite third-party bytecode, so use mods from sources you trust and test important worlds separately.

## Does it need internet access?

No. Normal transformation, caching, and verification are local. Optional native-version checks and explicit CLI archive downloads may use the network when enabled.

## Which versions work?

Retromod runs on Minecraft 1.20 through 26.2. Source support depends on the loader:

- Fabric: 1.14.4+
- NeoForge: 1.20.1+
- Forge: 1.12.2+

Coverage before 1.16.5 is experimental.

## Does it work on servers?

Yes. Install Retromod on every side that loads transformed code. Clients do not need it for a genuinely server-only mod.

Paper, Spigot, Bukkit, and Purpur plugins are not supported. Retromod targets Fabric, NeoForge, and Forge mods.

## Does it work with modpacks?

Yes. Keep old Fabric mods in `retromod-input/` so Retromod can process them before Fabric's version check. For repeatable distribution, prepare the pack with the CLI.

## What happens to dependencies?

Retromod relaxes stale version ranges when it knows the dependency can be bridged. It does not invent a missing library. Install required dependencies unless Retromod provides a documented polyfill.

## Should Minecraft 26.2 use OpenGL or Vulkan?

Use OpenGL for translated mods. Old rendering code often assumes OpenGL, while Vulkan is the new default. Retromod selects OpenGL automatically unless you already chose a backend.

## Does it work with OptiFine, Sodium, Iris, or Create?

Simple integrations may work, but renderer replacements and deep coremods are the hardest category and often cannot be translated. Check [Mods That Can't Be Translated]({{ '/incompatible-mods' | relative_url }}) and the [compatibility database]({{ '/compatdb' | relative_url }}).

## What does a modified-build warning mean?

The running classes do not match the embedded release hash. That can be a legitimate local build or fork. Compare the jar's SHA-256 with the value on the official download page if the change was unexpected. See [Authenticity]({{ '/authenticity' | relative_url }}).

## Can I use Retromod commercially?

Yes. Retromod uses the MIT license. You still need to follow the licenses of Minecraft, the loader, and every mod in your pack.

## Can I contribute?

Yes. Mapping fixes and focused redirects are good first changes. See [Contributing]({{ '/contributing' | relative_url }}).
