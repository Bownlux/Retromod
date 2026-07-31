# Security Policy

## Supported Releases

Security fixes are made for the active 1.3 pre-release line and the latest 1.2 stable release. Older lines may be fixed only when the same change applies cleanly.

## Report a Vulnerability

Please do not open a public issue for a security problem.

Use [GitHub's private vulnerability reporting](https://github.com/Bownlux/Retromod/security/advisories/new) or email **security@revivalsmp.net**.

Include:

- what you found and why it matters
- the Retromod version and loader
- steps or a small sample that reproduces it
- any suggested fix, if you have one

We aim to acknowledge a report within 48 hours and share an initial assessment within one week. Release timing depends on the severity and the work needed to verify the fix.

## In Scope

- unsafe bytecode or class injection caused by Retromod
- path traversal, zip slip, or unsafe jar extraction
- cache poisoning or writes outside Retromod's game-directory paths
- malicious mapping or metadata input that crosses a security boundary
- flaws in Retromod's update or download handling

## Out of Scope

- vulnerabilities in Minecraft, a mod loader, or a transformed mod
- ordinary compatibility failures or crashes
- social engineering
- resource exhaustion from unusually large jars, unless it crosses another security boundary

## Design Notes

Retromod treats every mod jar as untrusted input. Archive paths are checked before writing, transformed files stay inside Retromod-owned folders, and replacement classes come from the installed Retromod build rather than the network.

Mods still run with the same trust as any other Minecraft mod. Retromod does not sandbox or audit their behavior.

Official builds include a hash of Retromod's own classes. This can reveal accidental changes or a casual repack, but it is not a digital signature. For a real download check, compare the jar's SHA-256 with the value on the release page. See [Authenticity](docs/authenticity.md).
