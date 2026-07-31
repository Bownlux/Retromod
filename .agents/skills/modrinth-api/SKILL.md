---
name: modrinth-api
description: Find and download test mods through the Modrinth API. Use when reproducing compatibility reports or assembling a loader-and-version-specific test set.
argument-hint: "search-query loader mc-version (e.g. sodium fabric 1.21.1, jei neoforge)"
---

# Modrinth API

Use the public API to find an exact project version and preserve enough metadata to reproduce the test later.

## API Base URL
```
https://api.modrinth.com/v2
```

## Search for Mods

```bash
# Search by name with loader filter
curl -s 'https://api.modrinth.com/v2/search?query=<name>&facets=%5B%5B"project_type:mod"%5D,%5B"categories:<loader>"%5D%5D&limit=5'

# Parse results
curl -s '<url>' | python3 -c "
import sys, json
data = json.load(sys.stdin)
for h in data['hits']:
    print(h['slug'], h['title'], h['downloads'])
"
```

## Get Mod Versions

```bash
# Get versions for a specific loader
curl -s 'https://api.modrinth.com/v2/project/<slug>/version?loaders=%5B"<loader>"%5D&limit=10'

# Parse versions
curl -s '<url>' | python3 -c "
import sys, json
versions = json.load(sys.stdin)
for v in versions[:10]:
    print(v['version_number'], v['game_versions'], v['files'][0]['filename'])
"
```

## Download a Mod

```bash
# Get download URL from version ID
curl -s 'https://api.modrinth.com/v2/version/<version-id>' | python3 -c "
import sys, json
v = json.load(sys.stdin)
for f in v['files']:
    print(f['filename'], f['url'])
"

# Download
curl -sL -o output.jar "<download-url>"
```

## Common Facets

```
# Filter by loader
"categories:fabric"
"categories:neoforge"
"categories:forge"

# Filter by MC version
"versions:1.21.1"
"versions:26.1"

# Filter by project type
"project_type:mod"
"project_type:modpack"
"project_type:resourcepack"
```

## Example Test Mods by Loader

### Fabric
- sodium, lithium, iris, modmenu, cloth-config, fabric-api
- appleskin, mousetweaks, dynamic-fps, nochatreports

### NeoForge
- jei, jade, waystones, xaeros-minimap
- create, curios, architectury

### Both
- voicechat, notenoughcrashes, e4mc

## URL Encoding

Facets use JSON array format, URL-encoded:
- `[["a"],["b"]]` → `%5B%5B"a"%5D,%5B"b"%5D%5D`
- Loaders: `%5B"fabric"%5D` → `["fabric"]`

## API Etiquette
- Public endpoints do not require authentication.
- Cache responses during corpus work and avoid repeated requests for the same project.
- Record the project slug and version ID alongside downloaded jars.
