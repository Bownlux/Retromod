# Compatibility Database Data

Each YAML file records one tested combination of mod, source version, host version, loader, and Retromod build. Separate files keep community report pull requests independent.

Use the GitHub compatibility report form when possible. The workflow creates the YAML file automatically.

## Badges

- `diamond`: behaves like a native build
- `gold`: playable with minor issues
- `iron`: core features work, but something noticeable is broken
- `copper`: loads with major missing features
- `borked`: crashes or is unusable

## Fields

```text
name, badge, mod_version, source_mc, host_mc, loader,
retromod, links, summary, details, reporter, date, issue
```

Use `bundle: true` and a `mods` list for a tested group. A bundle has one overall badge. If one mod caused the failure, submit a separate report for it too.

Keep summaries specific. "Loads, but custom blocks do not render" is more useful than "works."
