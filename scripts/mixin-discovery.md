# Mixin discovery tooling: usage guide

Find the mixins that need translating between two Minecraft versions *before* a mod
crashes, instead of chasing it reactively through a log (see pitfalls #15/#48 in
`CLAUDE.md`). Four tools:

| Tool | Kind | Answers |
|------|------|---------|
| `mixin-scan` | CLI (Java, ASM) | What mixins/injectors exist across these jars? |
| `scripts/mixin-rank.py` | Python | Which targets are hit by the most mods? (worklist) |
| `scripts/mixin-crossjoin.py` | Python | Which target classes moved or disappeared between two versions? |
| `scripts/mixin-method-breaks.py` | Python | Which target methods disappeared while their classes survived? |
| `scripts/mixin-refmap-harvest.py` | Python | What do the refmaps reference? (no-JDK cross-check) |

The scanner produces JSON; the three Python tools consume it. All Python tools are
stdlib-only (Python 3).

Reminder: every result here is a CANDIDATE. A predicted break is a hypothesis about a
target that moved or changed signature; confirm correctness with the in-game or headless
verify, never from this output alone.

---

## 1. `mixin-scan` (the scanner)

ASM scan of mod jars into an injector-level JSON table of every `@Mixin` target. Its deps
are not bundled into a runnable artifact, so invoke it through `mvn exec:java` rather than
`java -jar`:

```bash
cd /Users/rossi/Development/Minecraft/RetroMod/Retromod

mvn -q exec:java \
  -Dexec.mainClass=com.retromod.cli.RetromodCli \
  -Dexec.args="mixin-scan <dir-or-jar> [<more>...] [--json <out>] [--top N]"
```

Concrete example, scanning a single fodder jar and writing the JSON table:

```bash
mvn -q exec:java \
  -Dexec.mainClass=com.retromod.cli.RetromodCli \
  -Dexec.args="mixin-scan audit-workspace/transformed/Wynnventory-0.2.2-transformed.jar --json scan-wynn.json --top 20"
```

Scan a whole directory (recurses for `*.jar`) and keep the JSON for the scripts:

```bash
mvn -q exec:java \
  -Dexec.mainClass=com.retromod.cli.RetromodCli \
  -Dexec.args="mixin-scan audit-workspace/transformed --json scan.json --top 30"
```

Notes:
- Multiple inputs are allowed: pass several dirs/jars before the flags.
- `--json <out>` writes the machine-readable table (frozen schema below). Without it you
  still get the human summary on stdout.
- `--top N` prints the N most-referenced `targetClass::targetMethod` pairs and an
  injector histogram.
- A corrupt or non-mixin jar is skipped and counted, it does not abort the run.

Output JSON schema (one record per injector handler; mixins with no injector still emit a
record with `injector: null` so target-class coverage is captured):

```json
{
  "scannedJars": 8,
  "records": [
    {
      "jar": "Wynnventory-0.2.2-transformed.jar",
      "mixinClass": "com/wynnventory/mixin/TradeMarketScannerMixin",
      "targetClasses": ["net/minecraft/..."],
      "applied": true,
      "handler": "onScan",
      "handlerDesc": "(...)V",
      "injector": "Inject",
      "targetSelectors": ["someMethod()V"],
      "at": ["HEAD"],
      "capturesLocal": false
    }
  ]
}
```

---

## 2. `scripts/mixin-rank.py` (frequency worklist)

Clusters scan records by `(targetClass, targetMethod)` and by target class alone, counting
distinct jars and distinct handlers. Sorts most-referenced first, so you translate the
highest-leverage targets first.

```bash
python3 scripts/mixin-rank.py scan.json
python3 scripts/mixin-rank.py scan.json --out worklist.txt
python3 scripts/mixin-rank.py scan.json --tsv > worklist.tsv
```

Multiple scan files can be passed at once (they are merged):

```bash
python3 scripts/mixin-rank.py scan-wynn.json scan-replaymod.json
```

Human mode prints the ranked target table plus an injector-type histogram; `--tsv` emits
the same ranking machine-readably.

---

## 3. `scripts/mixin-crossjoin.py` (predicted breaks for a version jump)

Joins a scan against a `scripts/harvest-mc-diff.py` output directory and reports the mixins
whose target classes moved, were renamed, or were removed between the two MC versions,
ranked by corpus frequency, with a suggested mechanism.

### End-to-end: scan a corpus, rank it, then predict breaks for a real jump

Local MC jars for a genuine diff are under
`~/Library/Application Support/minecraft/versions/`. This example jumps
`26.1-snapshot-10` to `26.2`.

```bash
cd /Users/rossi/Development/Minecraft/RetroMod/Retromod
MCV="$HOME/Library/Application Support/minecraft/versions"

# a) Scan the mod corpus into a JSON table.
mvn -q exec:java \
  -Dexec.mainClass=com.retromod.cli.RetromodCli \
  -Dexec.args="mixin-scan audit-workspace/transformed --json scan.json --top 30"

# b) Rank it to get the translate-first worklist.
python3 scripts/mixin-rank.py scan.json --out worklist.txt

# c) Generate a REAL MC class diff between the two version jars.
python3 scripts/harvest-mc-diff.py \
  "$MCV/26.1-snapshot-10/26.1-snapshot-10.jar" \
  "$MCV/26.2/26.2.jar" \
  diff-261s10-to-262

# d) Cross-join the scan against that diff: predicted breaks for the jump.
python3 scripts/mixin-crossjoin.py --scan scan.json --diff diff-261s10-to-262
```

Step (c) produces `moves.txt`, `renames.txt`, `removed.txt`, and `added.txt` in
`diff-261s10-to-262/`. Step (d) reads those and emits the predicted-break worklist with
columns: freq, targetClass, targetMethod, injector, reason (REMOVED / MOVED / RENAMED),
suggestedMechanism (class-redirect / resignature / blocklist), and example jars.

**Namespace alignment (important).** The cross-join matches target class names by exact
string, so the scan and the diff must be in the SAME namespace. A `harvest-mc-diff` between
two 26.1+ MC jars is Mojang-named, so it aligns with NeoForge/Forge mods (Mojang-named) and
with Fabric mods only AFTER the intermediary to Mojang harvest. A distributed Fabric mod
still ships intermediary names (`net/minecraft/class_634`), which will not intersect a
Mojang-named diff, so the cross-join reports no breaks for it (a true negative, not a miss).
See CLAUDE.md pitfall about intermediary vs Mojang namespaces. To cross-join a raw
intermediary Fabric scan, diff two intermediary-mapped jars, or map the scan targets through
`mixin-refmap-harvest` (which carries the human to intermediary bridge) first.

### Optional: flag method signature drift

Pass `--resig <file>`, a list of target-method names known to have signature drift (for
example harvested from `MixinHandlerResignature` SIGNATURE_CHANGES, or hand-written). Any
record whose target method is in that set is additionally flagged METHOD SIGNATURE DRIFT
with a resignature suggestion.

```bash
python3 scripts/mixin-crossjoin.py --scan scan.json --diff diff-261s10-to-262 --resig resig.txt
```

---

## 4. `scripts/mixin-refmap-harvest.py` (pure-JSON cross-check)

Pulls every `*refmap.json` out of the jars and inventories the referenced members per
mixin class. No bytecode and no JDK needed, so it runs anywhere and serves as an
independent cross-check against the ASM scanner.

```bash
python3 scripts/mixin-refmap-harvest.py audit-workspace/transformed
python3 scripts/mixin-refmap-harvest.py audit-workspace/transformed/Wynnventory-0.2.2-transformed.jar --out refmap-inventory.json
```

It parses the refmap `mappings` (per mixin class: human ref to intermediary ref) and any
per-environment `data` sub-maps, then emits a per-mixin-class member inventory as JSON plus
a summary. Where the scanner reports a target the refmap does not (or vice versa), that
discrepancy is worth a look.

---

## 5. `scripts/mixin-method-breaks.py` (method-level breaks)

`mixin-crossjoin` finds moved and removed classes. This companion catches the next case:
the class still exists, but an injector's target method does not. It follows known class
moves and the target's superclass chain before reporting a method as missing.

```bash
python3 scripts/mixin-method-breaks.py <scan.json> <26.2-client.jar> [--class-moves <tsv>]
```

The output ranks targets by the number of jars that use them and labels each one
`METHOD_GONE` or `CLASS_GONE`. The scan must use Mojang names, as the target jar does.
Map Fabric intermediary names first.

Treat the report as a worklist, not a verdict. Accessor selectors may refer to fields,
known render rewrites may be intentionally unsupported, and existing method redirects are
not consulted by this script.

---

## Typical flow

1. `mixin-scan` a corpus into `scan.json`.
2. `mixin-rank.py` for the worklist (what is worth translating first).
3. `harvest-mc-diff.py` between the old and new MC jars, then `mixin-crossjoin.py` for the
   predicted breaks on that specific jump.
4. `mixin-refmap-harvest.py` as a no-JDK cross-check on the same corpus.
5. Verify the top candidates in-game or headless before treating any of them as confirmed.
