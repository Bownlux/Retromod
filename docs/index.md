---
title: Home
layout: default
nav_order: 1
description: "Run older Minecraft mods on current Fabric, Quilt, NeoForge, and Forge."
permalink: /
---

# Retromod

Retromod translates older Minecraft mods while the game loads. It updates bytecode, mixins, metadata, and selected removed APIs without requiring the original source.

<div class="retromod-hero-cta" style="margin: 1.5em 0;">
  <a class="retromod-btn retromod-btn-primary" href="https://modrinth.com/mod/retromod">Download on Modrinth</a>
  <a class="retromod-btn retromod-btn-secondary" href="https://www.curseforge.com/minecraft/mc-mods/retromod">CurseForge</a>
  <a class="retromod-btn retromod-btn-ghost" href="{{ '/installation' | relative_url }}">Install guide</a>
  <a class="retromod-btn retromod-btn-ghost" href="https://github.com/Bownlux/Retromod">Source</a>
</div>

Published host jars cover Fabric, Quilt, and Forge on Minecraft 1.20 through 26.2, plus NeoForge on 1.20.1 through 26.2. Quilt uses the shared Fabric artifact, not a separate loader artifact. Source support reaches back to 1.12.2 on Forge, 1.14.4 on Fabric, and 1.20.1 on NeoForge. GitHub releases also include an executable standalone CLI jar for offline transforms.

## Start Here

- [Installation]({{ '/installation' | relative_url }})
- [Configuration]({{ '/config' | relative_url }})
- [Troubleshooting]({{ '/troubleshooting' | relative_url }})
- [Compatibility database]({{ '/compatdb' | relative_url }})
- [FAQ]({{ '/faq' | relative_url }})

## What to Expect

Retromod works best with content mods, quality-of-life mods, libraries, and recipe viewers. It can repair many renamed or moved APIs, but it cannot recreate an entire rendering engine or loader subsystem.

Mods such as Create, OptiFine, Flywheel, and Veil are generally outside its scope. See [Mods That Can't Be Translated]({{ '/incompatible-mods' | relative_url }}).

Original mod jars are backed up, but world saves are not. Back up important worlds and test save-affecting mods in a copied instance. A successful transform still needs an in-game test because a mod can load while one feature remains incompatible.

## More

- [In-game UI]({{ '/gui' | relative_url }})
- [CLI]({{ '/cli' | relative_url }})
- [Verify transforms]({{ '/verify-transforms' | relative_url }})
- [Architecture]({{ '/architecture' | relative_url }})
- [Contributing]({{ '/contributing' | relative_url }})
- [Writing an addon]({{ '/addons' | relative_url }})
- [Authenticity]({{ '/authenticity' | relative_url }})
