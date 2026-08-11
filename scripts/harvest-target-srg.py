#!/usr/bin/env python3
"""Build an owner-qualified Mojang-to-SRG table for one Forge target.

The table joins MCPConfig's obfuscated-to-SRG mapping with Mojang's official
mapping on the obfuscated owner and member signature. Runtime remapping uses
the owner and descriptor so ordinary names such as ``get`` are never guessed.
"""

import argparse
import json
import re
import urllib.request
import zipfile
from pathlib import Path


CLASS_RE = re.compile(r"^(\S+) -> (\S+):$")
MEMBER_RE = re.compile(
    r"^\s+(?:\d+:\d+:)?(\S+) (\w+|<init>|<clinit>)(\(.*\))? -> (\S+)$"
)
PRIMITIVES = {
    "int": "I", "long": "J", "short": "S", "byte": "B", "char": "C",
    "boolean": "Z", "float": "F", "double": "D", "void": "V",
}
USER_AGENT = {"User-Agent": "Retromod target SRG mapping generator"}


def read_json(url):
    request = urllib.request.Request(url, headers=USER_AGENT)
    with urllib.request.urlopen(request) as response:
        return json.load(response)


def download(url, destination):
    if destination.exists() and destination.stat().st_size > 0:
        return destination
    request = urllib.request.Request(url, headers=USER_AGENT)
    with urllib.request.urlopen(request) as response:
        destination.write_bytes(response.read())
    return destination


def official_mapping_paths(version, work_dir):
    manifest = read_json("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
    version_url = next(entry["url"] for entry in manifest["versions"]
                       if entry["id"] == version)
    metadata = read_json(version_url)
    paths = []
    for side in ("client_mappings", "server_mappings"):
        item = metadata["downloads"].get(side)
        if item is None:
            continue
        paths.append(download(item["url"], work_dir / f"{side}-{version}.txt"))
    return paths


def type_descriptor(type_name):
    arrays = ""
    while type_name.endswith("[]"):
        arrays += "["
        type_name = type_name[:-2]
    primitive = PRIMITIVES.get(type_name)
    if primitive is not None:
        return arrays + primitive
    return arrays + "L" + type_name.replace(".", "/") + ";"


def method_descriptor(return_type, arguments):
    inner = arguments[1:-1]
    parameters = "" if not inner else "".join(
        type_descriptor(argument.strip()) for argument in inner.split(",")
    )
    return "(" + parameters + ")" + type_descriptor(return_type)


def obfuscated_argument_descriptor(arguments, mojang_to_obfuscated):
    inner = arguments[1:-1]
    if not inner:
        return "()"

    def convert(type_name):
        arrays = ""
        while type_name.endswith("[]"):
            arrays += "["
            type_name = type_name[:-2]
        primitive = PRIMITIVES.get(type_name)
        if primitive is not None:
            return arrays + primitive
        owner = type_name.replace(".", "/")
        return arrays + "L" + mojang_to_obfuscated.get(owner, owner) + ";"

    return "(" + "".join(convert(argument.strip()) for argument in inner.split(",")) + ")"


def parse_official(paths):
    obfuscated_to_mojang = {}
    fields = {}
    methods = {}
    raw_methods = []
    current = None

    for path in paths:
        for line in path.read_text(encoding="utf-8").splitlines():
            class_match = CLASS_RE.match(line)
            if class_match:
                current = class_match.group(2).replace(".", "/")
                obfuscated_to_mojang[current] = class_match.group(1).replace(".", "/")
                continue
            member_match = MEMBER_RE.match(line)
            if member_match is None or current is None:
                continue
            member_type, mojang_name, arguments, obfuscated_name = member_match.groups()
            if arguments is None:
                fields[(current, obfuscated_name)] = (mojang_name, type_descriptor(member_type))
            else:
                raw_methods.append((current, obfuscated_name, mojang_name,
                                    member_type, arguments))

    mojang_to_obfuscated = {value: key for key, value in obfuscated_to_mojang.items()}
    for owner, obfuscated_name, mojang_name, return_type, arguments in raw_methods:
        argument_descriptor = obfuscated_argument_descriptor(arguments, mojang_to_obfuscated)
        methods[(owner, obfuscated_name, argument_descriptor)] = (
            mojang_name, method_descriptor(return_type, arguments)
        )
    return obfuscated_to_mojang, fields, methods


def joined_tsrg(mcp_zip, work_dir):
    output = work_dir / "joined.tsrg"
    with zipfile.ZipFile(mcp_zip) as archive:
        output.write_bytes(archive.read("config/joined.tsrg"))
    return output


def build_rows(tsrg_path, obfuscated_to_mojang, fields, methods):
    lines = tsrg_path.read_text(encoding="utf-8").splitlines()
    version_two = bool(lines and lines[0].startswith("tsrg2"))
    current = None
    rows = set()

    for line in lines:
        if not line or line.startswith("tsrg2"):
            continue
        if not line.startswith("\t"):
            current = line.split(" ")[0]
            continue
        if line.startswith("\t\t") or current is None:
            continue
        owner = obfuscated_to_mojang.get(current)
        if owner is None:
            continue
        parts = line.strip().split(" ")
        if (version_two and len(parts) == 3) or (not version_two and len(parts) == 2):
            obfuscated_name, srg_name = parts[:2]
            mapped = fields.get((current, obfuscated_name))
            if mapped is not None and srg_name.startswith("f_"):
                mojang_name, descriptor = mapped
                rows.add(("FIELD", owner, mojang_name, descriptor, srg_name))
        elif (version_two and len(parts) == 4) or (not version_two and len(parts) == 3):
            obfuscated_name, descriptor, srg_name = parts[:3]
            arguments = descriptor[:descriptor.rfind(")") + 1]
            mapped = methods.get((current, obfuscated_name, arguments))
            if mapped is not None and srg_name.startswith("m_"):
                mojang_name, mojang_descriptor = mapped
                if mojang_name not in ("<init>", "<clinit>"):
                    rows.add(("METHOD", owner, mojang_name, mojang_descriptor, srg_name))
    return sorted(rows)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("version")
    parser.add_argument("--mcp", required=True, type=Path,
                        help="MCPConfig zip for the target version")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--work-dir", type=Path, default=Path("target/target-srg-work"))
    args = parser.parse_args()

    args.work_dir.mkdir(parents=True, exist_ok=True)
    official = official_mapping_paths(args.version, args.work_dir)
    obfuscated_to_mojang, fields, methods = parse_official(official)
    rows = build_rows(joined_tsrg(args.mcp, args.work_dir),
                      obfuscated_to_mojang, fields, methods)

    header = [
        f"# Forge target SRG mappings for Minecraft {args.version}",
        "# Generated by scripts/harvest-target-srg.py from MCPConfig and Mojang official mappings.",
        "# KIND<TAB>MOJANG_OWNER<TAB>MOJANG_NAME<TAB>MOJANG_DESC<TAB>TARGET_SRG_NAME",
    ]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(header + ["\t".join(row) for row in rows]) + "\n",
                           encoding="utf-8")
    print(f"wrote {len(rows)} mappings to {args.output}")


if __name__ == "__main__":
    main()
