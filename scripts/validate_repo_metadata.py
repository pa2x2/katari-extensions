#!/usr/bin/env python3

from pathlib import Path

from build_repo import ROOT, discover_modules


MODULE_BUILD_FILE = "src/*/*/build.gradle.kts"
MODULE_METADATA_FILE = "src/*/*/repo-metadata.json"


def main() -> None:
    module_dirs = {path.parent for path in ROOT.glob(MODULE_BUILD_FILE)}
    metadata_dirs = {path.parent for path in ROOT.glob(MODULE_METADATA_FILE)}

    missing_metadata = sorted(module_dirs - metadata_dirs)
    if missing_metadata:
        missing_paths = ", ".join(path.relative_to(ROOT).as_posix() for path in missing_metadata)
        raise SystemExit(f"Missing repo-metadata.json for module(s): {missing_paths}")

    orphaned_metadata = sorted(metadata_dirs - module_dirs)
    if orphaned_metadata:
        orphaned_paths = ", ".join(path.relative_to(ROOT).as_posix() for path in orphaned_metadata)
        raise SystemExit(f"Found repo-metadata.json without build.gradle.kts for module(s): {orphaned_paths}")

    discover_modules()


if __name__ == "__main__":
    main()
