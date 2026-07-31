---
title: Authenticity
nav_order: 7
---

# Build Integrity

Official releases embed a SHA-256 hash of Retromod's compiled classes. At startup, Retromod compares that value with the classes it is running.

This is an integrity check, not a cryptographic signature. There is no secret key, so a determined editor could replace both the code and the embedded hash.

## Statuses

- **VERIFIED:** the classes match the embedded release hash.
- **MODIFIED:** the classes differ. This is normal for local builds, forks, and repacks.
- **IMPOSTOR:** the manifest does not identify the jar as Retromod.
- **UNKNOWN:** no usable hash is available, which is common in development.

No status disables features.

## Verify a Download

For stronger verification, compare the jar's SHA-256 with the value published on the official GitHub or Modrinth download page. That value is outside the jar and cannot be replaced by repacking the file.

## Forks

Retromod is MIT licensed. Forks may change or remove the notice. Keeping clear branding and an honest build status helps users understand what they installed.
