# Security audit, 1.3.0

Audited 2026-08-23 against the 1.3.0 pre-release line. Fixed in stable 1.3.0, released 2026-09-15.
Held private until that release, per the disclosure policy in `SECURITY.md`.

None of these is remote code execution. Each needs local file placement, local filesystem control,
or a transform run against a crafted input.

## Findings

Retromod treats every mod jar as untrusted input, and several paths did not hold that line.

- Archive, metadata, config and log readers lacked consistent byte, entry, depth and line limits. A
  crafted or oversized input could exhaust memory, disk or worker capacity mid-transform.
- Archive extraction and publication did not share one policy for normalized aliases, symlinks,
  partial output and destination replacement. A failed run could damage an earlier output, or write
  outside the managed tree when a maintainer-controlled directory was redirected.
- A changed signed jar kept the original signature metadata, so it failed verification after an
  otherwise correct transform.
- Cache identity did not cover the whole transform context. A cached result could be reused across
  an incompatible source, target, execution mode or build.
- Parallel transforms could read another worker's mutable class state, so frame reconstruction or
  post-remap repair could use the wrong snapshot.
- Loader metadata precedence and Quilt routing differed between runtime, CLI, AOT and release
  artifacts, so a dual-metadata mod could be patched as the wrong loader.
- Pack conversion accepted malformed encodings, path aliases and reserved output names, and could
  overwrite an existing output or archive its source before finishing.
- Self-hash input framing did not separate names from payloads strongly enough.
- Four read boundaries allocated from input before applying limits: reflective resource JSON,
  extracted hierarchy lookup, classpath hierarchy fallback, and manifest parsing.

## Affected versions

The parsing, cache, signed-jar, publication and concurrency findings reach back before
`1.3.0-snapshot.9`, including published `1.3.0-snapshot.8` where the path exists. The Quilt and pack
findings were only ever in unpublished development code. Confirm exact ranges from release tags.

## Fixes

Shared bounded JSON and archive readers with strict UTF-8 and depth checks. Traversal, symlink,
duplicate, entry-count and expanded-size checks on every archive path. Staged archive publication
and transactional pack publication with rollback. Stale signing artifacts dropped whenever bytes
change, byte-exact copies untouched. Cache keys bound to source hash, target, executable hash and
execution mode. Per-jar hierarchy and loader state isolated across workers. Quilt metadata made
authoritative. Framed self-hash domains and atomic checksum manifests. Fail-closed release artifact
validation. Bounded reflective reload, hierarchy lookup and manifest reads.

## Verification

Full suite green. OSV returned no known vulnerability for any direct Maven or Python dependency on
2026-08-23. Release workflow actions are pinned to commit hashes. The sealed release matrix
validated, every artifact carrying the same embedded self-hash. Live 26.2 Fabric and Quilt runs
reached the title screen with no crash report.

Dependabot, code scanning and secret scanning are not enabled, and were not counted as passing.
