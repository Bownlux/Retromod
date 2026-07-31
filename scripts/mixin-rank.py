#!/usr/bin/env python3
"""Rank the mixin corpus into a translation worklist from mixin-scan JSON.

Consumes one or more JSON files produced by the CLI `mixin-scan` command
(schema: {"scannedJars": N, "records": [...]}) and answers "which MC members
are mixin'd the hardest across the whole corpus?", so a version jump can be
triaged targets-first instead of crash-first.

Usage:
    python3 scripts/mixin-rank.py <scan.json> [<more-scan.json>...] [--top N] [--tsv] [--out FILE]

Clusters records by (targetClass, bare target-method name from the first
targetSelector), counts DISTINCT jars and DISTINCT handlers per cluster, and
sorts descending by jar-count then handler-count (the members many independent
mods touch are the ones a version break hurts most). Also prints an
injector-type histogram and a targetClass-only ranking.

Human-readable tables by default; `--tsv` emits tab-separated rows for piping
into another tool (feeds naturally into mixin-crossjoin.py). Sits alongside the
harvest-* / build-probe-db scripts: read-only, stdlib-only, re-runnable.
"""
import argparse
import json
import sys
from collections import Counter, defaultdict


def strip_selector(selector):
    """Reduce a mixin target selector to its bare member name.

    Selectors seen in the wild:
      "someMethod()V"                          -> someMethod
      "someMethod"                             -> someMethod
      "Lnet/minecraft/Foo;someMethod(...)V"    -> someMethod  (owner-prefixed / @Desc)
      "net/minecraft/Foo.someMethod(...)V"     -> someMethod
      "<init>(...)V"                           -> <init>
    Strips any owner prefix (';' or the last '.' before '(') and the descriptor.
    """
    if selector is None:
        return None
    s = selector.strip()
    if not s:
        return None
    # Drop the descriptor: everything from the first '(' onward.
    paren = s.find("(")
    if paren != -1:
        s = s[:paren]
    # Drop an owner prefix delimited by ';' (e.g. "Lnet/minecraft/Foo;name").
    semi = s.rfind(";")
    if semi != -1:
        s = s[semi + 1:]
    # Drop an owner prefix delimited by '.' (e.g. "net/minecraft/Foo.name").
    dot = s.rfind(".")
    if dot != -1:
        s = s[dot + 1:]
    # Drop a residual owner prefix delimited by '/' with no descriptor.
    slash = s.rfind("/")
    if slash != -1:
        s = s[slash + 1:]
    return s or None


def first_selector(record):
    """Return the first targetSelector string of a record, or None."""
    sels = record.get("targetSelectors")
    if isinstance(sels, list):
        for entry in sels:
            if isinstance(entry, str) and entry.strip():
                return entry
        return None
    if isinstance(sels, str) and sels.strip():
        return sels
    return None


def load_scan(path):
    """Load one scan file, returning (scannedJars, records). Fail-safe per file."""
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError) as exc:
        print("skip %s: %s" % (path, exc), file=sys.stderr)
        return 0, []
    if not isinstance(data, dict):
        print("skip %s: top level is not an object" % path, file=sys.stderr)
        return 0, []
    scanned = data.get("scannedJars")
    scanned = scanned if isinstance(scanned, int) else 0
    records = data.get("records")
    records = records if isinstance(records, list) else []
    return scanned, [r for r in records if isinstance(r, dict)]


def cluster(records):
    """Cluster records by (targetClass, bare-target-method).

    A record can carry several targetClasses; each contributes to its own
    cluster. Records with no targetClasses are grouped under a "?" class.
    Returns a dict keyed by (targetClass, method) -> {jars, handlers, injectors}.
    """
    clusters = defaultdict(lambda: {"jars": set(), "handlers": set(), "injectors": Counter()})
    for rec in records:
        method = strip_selector(first_selector(rec)) or "*"
        jar = rec.get("jar") or "?"
        mixin = rec.get("mixinClass") or "?"
        handler = rec.get("handler")
        # A handler identity that is unique per mixin class + handler name.
        handler_id = "%s#%s" % (mixin, handler if handler else "<none>")
        injector = rec.get("injector") or "(none)"
        targets = rec.get("targetClasses")
        if not isinstance(targets, list) or not targets:
            targets = ["?"]
        for tc in targets:
            key = (tc if tc else "?", method)
            bucket = clusters[key]
            bucket["jars"].add(jar)
            bucket["handlers"].add(handler_id)
            bucket["injectors"][injector] += 1
    return clusters


def rank_clusters(clusters):
    """Return cluster rows sorted desc by jar-count then handler-count."""
    rows = []
    for (tc, method), bucket in clusters.items():
        rows.append({
            "targetClass": tc,
            "targetMethod": method,
            "jarCount": len(bucket["jars"]),
            "handlerCount": len(bucket["handlers"]),
            "injectors": bucket["injectors"],
        })
    rows.sort(key=lambda r: (-r["jarCount"], -r["handlerCount"], r["targetClass"], r["targetMethod"]))
    return rows


def rank_classes(clusters):
    """Aggregate clusters up to targetClass only, sorted the same way."""
    by_class = defaultdict(lambda: {"jars": set(), "handlers": set()})
    for (tc, _method), bucket in clusters.items():
        by_class[tc]["jars"].update(bucket["jars"])
        by_class[tc]["handlers"].update(bucket["handlers"])
    rows = [{
        "targetClass": tc,
        "jarCount": len(b["jars"]),
        "handlerCount": len(b["handlers"]),
    } for tc, b in by_class.items()]
    rows.sort(key=lambda r: (-r["jarCount"], -r["handlerCount"], r["targetClass"]))
    return rows


def injector_histogram(records):
    """Count records per injector simple name (null -> '(none)')."""
    hist = Counter()
    for rec in records:
        hist[rec.get("injector") or "(none)"] += 1
    return hist


def top_injector(counter):
    """Return the dominant injector name for a cluster."""
    if not counter:
        return "(none)"
    return counter.most_common(1)[0][0]


def emit_human(out, scanned_total, records, member_rows, class_rows, hist, top):
    mixin_classes = {(r.get("jar"), r.get("mixinClass")) for r in records}
    handlers = sum(1 for r in records if r.get("handler"))
    w = out.write

    w("Mixin corpus rank\n")
    w("Scanned jars: %d\n" % scanned_total)
    w("Records: %d\n" % len(records))
    w("Distinct mixin classes: %d\n" % len(mixin_classes))
    w("Injector handlers: %d\n\n" % handlers)

    w("Top %d targets by (targetClass :: method), ranked by distinct jars\n" % top)
    w("%5s %5s  %-11s %s\n" % ("jars", "hdlr", "injector", "target"))
    for r in member_rows[:top]:
        w("%5d %5d  %-11s %s :: %s\n" % (
            r["jarCount"], r["handlerCount"], top_injector(r["injectors"]),
            r["targetClass"], r["targetMethod"]))
    w("\n")

    w("Top %d target classes, ranked by distinct jars\n" % top)
    w("%5s %5s  %s\n" % ("jars", "hdlr", "targetClass"))
    for r in class_rows[:top]:
        w("%5d %5d  %s\n" % (r["jarCount"], r["handlerCount"], r["targetClass"]))
    w("\n")

    w("Injector histogram\n")
    for name, count in hist.most_common():
        w("%6d  %s\n" % (count, name))
    w("\n")


def emit_tsv(out, member_rows, class_rows, hist):
    w = out.write
    w("#member\tjars\thandlers\tinjector\ttargetClass\ttargetMethod\n")
    for r in member_rows:
        w("member\t%d\t%d\t%s\t%s\t%s\n" % (
            r["jarCount"], r["handlerCount"], top_injector(r["injectors"]),
            r["targetClass"], r["targetMethod"]))
    w("#class\tjars\thandlers\ttargetClass\n")
    for r in class_rows:
        w("class\t%d\t%d\t%s\n" % (r["jarCount"], r["handlerCount"], r["targetClass"]))
    w("#injector\tcount\tname\n")
    for name, count in hist.most_common():
        w("injector\t%d\t%s\n" % (count, name))


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Rank a mixin-scan corpus into a translation worklist.")
    parser.add_argument("scan", nargs="+", help="one or more mixin-scan JSON files")
    parser.add_argument("--top", type=int, default=25, metavar="N",
                        help="rows to show in each human-readable table (default 25)")
    parser.add_argument("--tsv", action="store_true",
                        help="emit tab-separated rows instead of tables")
    parser.add_argument("--out", metavar="FILE",
                        help="write to FILE instead of stdout")
    args = parser.parse_args(argv)

    scanned_total = 0
    records = []
    for path in args.scan:
        scanned, recs = load_scan(path)
        scanned_total += scanned
        records.extend(recs)

    clusters = cluster(records)
    member_rows = rank_clusters(clusters)
    class_rows = rank_classes(clusters)
    hist = injector_histogram(records)

    out = open(args.out, "w", encoding="utf-8") if args.out else sys.stdout
    try:
        if args.tsv:
            emit_tsv(out, member_rows, class_rows, hist)
        else:
            emit_human(out, scanned_total, records, member_rows, class_rows, hist, args.top)
    finally:
        if args.out:
            out.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
