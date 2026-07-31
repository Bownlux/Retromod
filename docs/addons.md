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
