---
title: Authenticity
nav_order: 7
---

# Build Integrity

Official releases embed a SHA-256 hash of the executable release surface. It covers every class except loader-provided `org/objectweb/asm/`, loader-variant `javax/annotation/` stubs, and the verifier class that carries the hash. It also covers every `META-INF/services/` descriptor and Retromod's bundled transformation data. At startup, Retromod compares the embedded value with the running jar.

This is an integrity check, not a cryptographic signature. There is no secret key, so a determined editor could replace both the code and the embedded hash.

## Statuses

- **VERIFIED:** the covered executable surface matches the embedded release hash.
- **MODIFIED:** covered code, providers, or transformation data differ. This is normal for local builds, forks, and repacks.
- **IMPOSTOR:** the manifest does not identify the jar as Retromod.
- **UNKNOWN:** no usable hash is available, which is common in development.

No status disables features.

## Verify a Download

For stronger verification, compare the whole jar's SHA-256 with `SHA256SUMS.txt` from the same official GitHub release. The manifest covers all 68 loader jars and the standalone CLI artifact.

For a complete extracted `dist/` tree on Linux:

```bash
cd dist
sha256sum --check SHA256SUMS.txt
```

On macOS:

```bash
cd dist
shasum -a 256 --check SHA256SUMS.txt
```

For one downloaded jar, print its value and compare it with the matching manifest line:

```bash
shasum -a 256 retromod-1.3.0-snapshot.8+26.2.jar
```

On Windows PowerShell:

```powershell
Get-FileHash .\retromod-1.3.0-snapshot.8+26.2.jar -Algorithm SHA256
```

Use the row for the same loader and Minecraft version. A Fabric jar and a Forge jar share a filename inside different distribution folders, but they are different files with different whole-file hashes.

## Forks

Retromod is MIT licensed. Forks may change or remove the notice. Keeping clear branding and an honest build status helps users understand what they installed.
