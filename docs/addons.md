---
title: Writing an Addon
layout: default
nav_order: 12.5
description: "Ship extra Retromod shims and polyfills as a separate mod."
---

# Writing an Addon

An addon can provide compatibility work that is too specific for Retromod core. It is a normal mod containing implementations of Retromod's public extension interfaces.

## Version Shims

Implement `com.retromod.core.VersionShim` to register a verified API change:

```java
public final class ExampleShim implements VersionShim {
    public String getShimName() { return "Example mod fix"; }
    public String getSourceVersion() { return "1.20.1"; }
    public String getTargetVersion() { return "1.21"; }
    public String getModLoaderType() { return "fabric"; }

    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerClassRedirect(
                "net/minecraft/old/Thing",
                "net/minecraft/new/Thing");
    }
}
```

Keep redirects owner- and descriptor-specific when working with methods.

Implement `com.retromod.core.AuxiliaryVersionShim` when `getSourceVersion()` and `getTargetVersion()` describe library API releases. Retromod selects that provider by loader. It does not compare those values with Minecraft or use the provider as a graph edge.

Implement `com.retromod.core.MinecraftVersionedApiShim` when those values describe Minecraft host versions for an API repair. Retromod keeps that provider outside the transition graph, but it registers the provider only when the selected host reaches `getTargetVersion()`. Explicit same-version transforms can apply both provider types.

## Polyfills

Implement `com.retromod.polyfill.PolyfillProvider` when an API was removed and needs a small replacement. Redirect the old name to a class in your addon's own package. Do not add classes to loader-owned packages.

## Service Registration

List implementations in standard service files:

```text
META-INF/services/com.retromod.core.VersionShim
META-INF/services/com.retromod.polyfill.PolyfillProvider
```

Each file contains one implementation class per line. Install the addon beside Retromod.

Compile against `VersionShim`, `PolyfillProvider`, and documented `RetromodTransformer` registration methods. Treat other implementation classes as internal.

## Licensing

Your addon uses your license. Permissively licensed fixes may be adopted into Retromod core with attribution. Pull requests to this repository are contributed under MIT.

See [Contributing]({{ '/contributing' | relative_url }}) for core changes.
