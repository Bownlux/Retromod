#!/usr/bin/env python3
"""Generate Retromod's release checksum manifest atomically."""

import argparse
import sys

from release_artifacts import (
    ReleaseArtifactError,
    generate_release_checksum_manifest,
)


def main():
    parser = argparse.ArgumentParser(
        description="Generate checksums for the exact Retromod release matrix."
    )
    parser.add_argument("--version", required=True)
    parser.add_argument("--dist", default="dist")
    parser.add_argument("--pom", default="pom.xml")
    arguments = parser.parse_args()

    try:
        count = generate_release_checksum_manifest(
            arguments.version,
            arguments.dist,
            arguments.pom,
        )
    except ReleaseArtifactError as error:
        print(error, file=sys.stderr)
        return 1

    print(count)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
