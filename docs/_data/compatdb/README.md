# Retromod community compatibility database

One FILE per entry (rendered by `docs/compatdb.html` via Jekyll's data
directory: `site.data.compatdb`). One entry per (mod + source version + host)
combination that someone actually ran. Add yours via the "Compatibility
report" issue form on GitHub (a workflow turns it into a PR adding
`issue-<N>.yml` here), or PR a new file directly. Per-entry files exist so
concurrent report PRs NEVER conflict with each other; do not merge entries
back into one shared file.

Badges (pick ONE):

- `diamond` — indistinguishable from running natively; everything works
- `gold`    — fully playable; minor or cosmetic quirks only
- `iron`    — core features work; something noticeable is broken or inert
- `copper`  — loads and runs, but major features are missing/inert
- `borked`  — crashes or unusable

Fields (top-level YAML mapping, NOT a list item):

    name, badge, mod_version, source_mc (version the mod was built for),
    host_mc, loader (fabric|neoforge|forge), retromod (version used),
    links: {modrinth|curseforge|github: url}, summary (one line),
    details (longer, optional), reporter, date (YYYY-MM-DD), issue (optional N)

BUNDLE reports ("I used all these mods at once"): set `bundle: true`, use
`name` as a label for the combo, and list the mods under `mods:` (each: name,
version, source_mc, link). One badge applies to the whole combo. If you can
attribute the failure to one mod, ALSO file a single-mod entry for it.

Keep details honest: "loads but X is broken" beats "works".
