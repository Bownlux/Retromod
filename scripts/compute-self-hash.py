#!/usr/bin/env python3
"""Compute Retromod's self-integrity hash for a built JAR.

Mirrors `SignatureVerifier.computeSelfHash` exactly. It hashes the executable
release surface in entry-name order: classes, ServiceLoader descriptors, and
Retromod transformation data. Loader-provided ASM, loader-variant annotation
stubs, and the verifier class are excluded. Output is 64 uppercase hex chars.
Entry names and bodies use fixed-width length framing.

Release flow:
  1. Build the final release jar (no further source changes after this).
  2. Run this over that jar.
  3. Embed the result into SignatureVerifier.EXPECTED_SELF_HASH programmatically.
  4. Rebuild - the verifier class is excluded from the hash, so re-embedding
     does not invalidate it; the release build then reports VERIFIED.

Usage:
  python3 scripts/compute-self-hash.py target/retromod-<version>.jar
"""
import hashlib
import sys
import zipfile

SELF_ENTRY = "com/retromod/security/SignatureVerifier.class"
HASH_DOMAIN = b"RETROMOD-SELF-HASH\x00V2"


def is_hashed_entry(name: str) -> bool:
    if name == SELF_ENTRY:
        return False
    if name.endswith(".class"):
        return not (
            name.startswith("org/objectweb/asm/")
            or name.startswith("javax/annotation/")
        )
    return (
        name.startswith("META-INF/services/")
        or name.startswith("retromod/")
        or name in {
            "intermediary-to-mojang.tsv",
            "mojang-class-moves-26.1.tsv",
            "retromod.mixins.json",
        }
    )


def compute_self_hash(jar_path: str) -> str:
    names = []
    with zipfile.ZipFile(jar_path) as z:
        seen = set()
        for n in z.namelist():
            if n.endswith("/"):
                continue
            if n in seen:
                raise ValueError(f"duplicate JAR entry: {n}")
            seen.add(n)
            if is_hashed_entry(n):
                names.append(n)
        names.sort()
        h = hashlib.sha256()
        h.update(HASH_DOMAIN)
        for n in names:
            name = n.encode("utf-8")
            body = z.read(n)
            h.update(len(name).to_bytes(4, byteorder="big", signed=False))
            h.update(name)
            h.update(len(body).to_bytes(8, byteorder="big", signed=False))
            h.update(body)
    return h.hexdigest().upper()


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: compute-self-hash.py <jar>", file=sys.stderr)
        sys.exit(2)
    print(compute_self_hash(sys.argv[1]))
