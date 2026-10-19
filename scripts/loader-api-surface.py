#!/usr/bin/env python3
"""Generate the host Fabric API surface files the loader API runtime reads.

Each file lists the public signatures of every Fabric API class under net/fabricmc/fabric/ in one
host line, as the intersection of the builds given for that line, so a target that exists in the
file exists in every one of those builds. Only signatures are written, never code.

Usage:
  python3 -I scripts/loader-api-surface.py --out-dir src/main/resources/retromod/loaderapi/fabric-26x \\
      --line 26.1=fabric-api-0.145.1+26.1.jar,fabric-api-0.155.3+26.1.2.jar \\
      --line 26.3=fabric-api-0.163.0+26.3.jar
  Add --check to regenerate in memory and fail when a checked-in file differs.

Fabric API jars are untrusted downloads. This script only reads zip entries and decodes class file
bytes; it never runs them.
"""
import argparse
import io
import json
import os
import struct
import sys
import zipfile

PREFIX = 'net/fabricmc/fabric/'
ACC_PUBLIC, ACC_PROTECTED, ACC_STATIC = 0x0001, 0x0004, 0x0008
ACC_FINAL, ACC_INTERFACE, ACC_ABSTRACT, ACC_SYNTHETIC = 0x0010, 0x0200, 0x0400, 0x1000
COLUMNS = ['kind', 'owner', 'name', 'desc', 'flags', 'super', 'interfaces']


def parse_class(data):
    """Return the class's name, access, super, interfaces, members and inner class table."""
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('not a class file')
    pos = 10
    count = struct.unpack_from('>H', data, 8)[0]
    pool = [None] * count
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            length = struct.unpack_from('>H', data, pos)[0]
            raw = data[pos + 2:pos + 2 + length]
            pool[i] = raw.replace(b'\xc0\x80', b'\x00').decode('utf-8', 'replace')
            pos += 2 + length
        elif tag in (3, 4):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = ('ref', struct.unpack_from('>H', data, pos)[0])
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag == 15:
            pos += 3
        else:
            raise ValueError('unknown constant pool tag %d' % tag)
        i += 1

    def utf(index):
        return pool[index] if isinstance(pool[index], str) else None

    def cls(index):
        return utf(pool[index][1]) if index else None

    access, this_index, super_index = struct.unpack_from('>HHH', data, pos)
    pos += 6
    interface_count = struct.unpack_from('>H', data, pos)[0]
    pos += 2
    interfaces = [cls(struct.unpack_from('>H', data, pos + 2 * k)[0]) for k in range(interface_count)]
    pos += 2 * interface_count

    def members():
        nonlocal pos
        out = []
        n = struct.unpack_from('>H', data, pos)[0]
        pos += 2
        for _ in range(n):
            flags, name_index, desc_index, attr_count = struct.unpack_from('>HHHH', data, pos)
            pos += 8
            for _ in range(attr_count):
                pos += 6 + struct.unpack_from('>I', data, pos + 2)[0]
            out.append((flags, utf(name_index), utf(desc_index)))
        return out

    fields = members()
    methods = members()
    inner = []
    attr_count = struct.unpack_from('>H', data, pos)[0]
    pos += 2
    for _ in range(attr_count):
        name_index, length = struct.unpack_from('>HI', data, pos)
        body = data[pos + 6:pos + 6 + length]
        pos += 6 + length
        if utf(name_index) == 'InnerClasses':
            n = struct.unpack_from('>H', body, 0)[0]
            for k in range(n):
                ic, oc, nm, acc = struct.unpack_from('>HHHH', body, 2 + 8 * k)
                inner.append((cls(ic), cls(oc) if oc else None, nm, acc))
    return {'name': cls(this_index), 'access': access, 'super': cls(super_index),
            'interfaces': interfaces, 'fields': fields, 'methods': methods, 'inner': inner}


def module_jars(archive):
    """Yield every nested module jar, depth first."""
    for info in archive.infolist():
        if info.filename.startswith('META-INF/jars/') and info.filename.endswith('.jar'):
            inner = zipfile.ZipFile(io.BytesIO(archive.read(info.filename)))
            yield inner
            yield from module_jars(inner)


def read_build(path):
    """Return the build's version, its Fabric Loader floor and its signature rows."""
    top = zipfile.ZipFile(path)
    meta = json.loads(top.read('fabric.mod.json').decode('utf-8'), strict=False)
    loader_floor = (meta.get('depends') or {}).get('fabricloader', '')
    parsed = {}
    for archive in [top] + list(module_jars(top)):
        for info in archive.infolist():
            name = info.filename
            if name.endswith('.class') and name.startswith(PREFIX) and '/mixin/' not in name:
                c = parse_class(archive.read(name))
                parsed[c['name']] = c
    rows = {}
    for name, c in parsed.items():
        access = visible_access(name, parsed)
        if access is None:
            continue
        flags = []
        if access & ACC_INTERFACE:
            flags.append('interface')
        elif access & ACC_ABSTRACT:
            flags.append('abstract')
        rows[('C', name, '', '')] = (','.join(flags), c['super'] or '', ','.join(c['interfaces']))
        final = bool(c['access'] & ACC_FINAL)
        for kind, members in (('F', c['fields']), ('M', c['methods'])):
            for flags_bits, member, desc in members:
                if flags_bits & ACC_SYNTHETIC or member == '<clinit>':
                    continue
                if not flags_bits & (ACC_PUBLIC | ACC_PROTECTED):
                    continue
                if flags_bits & ACC_PROTECTED and final:
                    continue
                member_flags = []
                if flags_bits & ACC_STATIC:
                    member_flags.append('static')
                if flags_bits & ACC_PROTECTED:
                    member_flags.append('protected')
                rows[(kind, name, member, desc)] = (','.join(member_flags), '', '')
    return meta.get('version', '?'), loader_floor, rows


def visible_access(name, parsed, memo=None):
    """The access a class has from another module, or None when it is not reachable."""
    memo = {} if memo is None else memo
    if name in memo:
        return memo[name]
    memo[name] = None
    c = parsed.get(name)
    if c is None:
        return None
    own = next((entry for entry in c['inner'] if entry[0] == name), None)
    if own is None:
        result = c['access'] if c['access'] & ACC_PUBLIC else None
    else:
        _, outer, simple, inner_access = own
        if outer is None or simple == 0 or not inner_access & (ACC_PUBLIC | ACC_PROTECTED):
            result = None
        elif visible_access(outer, parsed, memo) is None:
            result = None
        else:
            result = inner_access | (c['access'] & (ACC_INTERFACE | ACC_ABSTRACT))
    memo[name] = result
    return result


def version_key(version):
    """Sort key for Fabric API versions such as 0.145.1+26.1."""
    core = version.split('+', 1)[0]
    return tuple(int(part) if part.isdigit() else 0 for part in core.split('.'))


def render(line, builds):
    """The surface file text for one host line."""
    builds = sorted(builds, key=lambda build: version_key(build[0]))
    common = None
    for _, _, rows in builds:
        common = set(rows) if common is None else common & set(rows)
    oldest_rows = builds[0][2]
    out = ['# Fabric API surface for host line %s, generated by scripts/loader-api-surface.py.' % line,
           '# Signatures present in every build below. Do not edit by hand.',
           '# builds\t%s' % ','.join(build[0] for build in builds),
           '# min_fabric_api\t%s' % builds[0][0].split('+', 1)[0],
           '# min_fabric_loader\t%s' % builds[0][1],
           '\t'.join(COLUMNS)]
    for key in sorted(common):
        kind, owner, name, desc = key
        flags, superclass, interfaces = oldest_rows[key]
        out.append('\t'.join([kind, owner, name, desc, flags, superclass, interfaces]))
    return '\n'.join(out) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--out-dir', required=True)
    parser.add_argument('--line', action='append', required=True,
                        help='HOST=jar1,jar2 (one per host line)')
    parser.add_argument('--check', action='store_true',
                        help='fail when a checked-in file differs from a fresh run')
    args = parser.parse_args()
    differences = 0
    for spec in args.line:
        line, jars = spec.split('=', 1)
        builds = [read_build(path) for path in jars.split(',') if path]
        text = render(line, builds)
        target = os.path.join(args.out_dir, 'surface-%s.tsv' % line)
        if args.check:
            current = open(target, encoding='utf-8').read() if os.path.exists(target) else ''
            if current != text:
                print('surface-%s.tsv differs from the Fabric API builds given' % line)
                differences += 1
        else:
            with open(target, 'w', encoding='utf-8', newline='\n') as handle:
                handle.write(text)
            print('wrote %s (%d rows from %d build(s))'
                  % (target, text.count('\n') - 6, len(builds)))
    return 1 if differences else 0


if __name__ == '__main__':
    sys.exit(main())
